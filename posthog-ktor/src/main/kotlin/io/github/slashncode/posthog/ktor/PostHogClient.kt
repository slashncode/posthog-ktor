package io.github.slashncode.posthog.ktor

import com.posthog.FeatureFlagResult
import com.posthog.PersonProfiles
import com.posthog.server.PostHogCaptureOptions
import com.posthog.server.PostHogEvaluateFlagsOptions
import com.posthog.server.PostHogFeatureFlagEvaluations
import com.posthog.server.PostHogFeatureFlagResultOptions
import com.posthog.server.PostHogInterface
import com.posthog.server.PostHogRequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The application's PostHog client. Inside a call, or a [PostHogContext], captures without an
 * explicit distinct id use the context's. Every call is a no-op while PostHog is off (no project
 * token) or opted out, so code that captures needs no checks of its own.
 */
public class PostHogClient internal constructor(
    private val config: PostHogPluginConfig,
) {
    /** The official SDK's client, for everything this library does not wrap; null while PostHog is off. */
    public var sdk: PostHogInterface? = null
        internal set

    @Volatile private var optedOut: Boolean = config.optOut
    private val registered = ConcurrentHashMap<String, Any>()

    /** The project token (`phc_…`, public by design); null while PostHog is off. */
    public val projectToken: String? get() = config.projectToken?.takeIf { sdk != null }

    /** The ingestion host events are sent to. */
    public val host: String get() = config.host

    /** PostHog's app, for links: the `uiHost` option, else the cloud's app host for [host]. */
    public val uiHost: String get() = config.uiHost ?: host.replace(".i.posthog.com", ".posthog.com")

    /** Whether AI events go without prompts, outputs and states (the `privacyMode` option). */
    public val privacyMode: Boolean get() = config.privacyMode

    // getEarlyAccessFeatures' answers by stages, kept for a minute.
    internal val earlyAccessCache = ConcurrentHashMap<String, CachedFeatures>()

    /** A client that sends nothing, for calls that capture nothing. */
    internal val off: PostHogClient by lazy { PostHogClient(config) }

    /** Whether events actually go to PostHog: a project token is set and nobody opted out. */
    public val isEnabled: Boolean get() = sdk != null && !optedOut

    /** Whether capturing is turned off at runtime ([optOut]) or by the `optOut` option. */
    public val isOptedOut: Boolean get() = optedOut

    /** Stops capturing: nothing is sent and every flag is off until [optIn]. */
    public fun optOut() {
        optedOut = true
    }

    /** Captures again after [optOut]. */
    public fun optIn() {
        optedOut = false
    }

    /** Turns the official SDK's verbose logging on or off. */
    public fun debug(enabled: Boolean = true) {
        sdk?.debug(enabled)
    }

    /**
     * Super properties: properties every event of this client carries from now on, for the lifetime
     * of the application. An event's own properties and its context's win over them. Null values
     * are left out.
     */
    public fun register(properties: Map<String, Any?>) {
        registered.putAll(properties.withoutNulls())
    }

    /** Registers the super properties that are not registered yet; see [register]. */
    public fun registerOnce(properties: Map<String, Any?>) {
        properties.withoutNulls().forEach { (key, value) -> registered.putIfAbsent(key, value) }
    }

    /** Stops sending the super property [key]. */
    public fun unregister(key: String) {
        registered.remove(key)
    }

    /** The super properties registered now. */
    public val superProperties: Map<String, Any> get() = registered.toMap()

    /**
     * Captures [event] for [distinctId], or for the current context's distinct id. Without either it
     * is sent without a person profile, or dropped if `captureWithoutDistinctId` is off. Null
     * property values are left out.
     *
     * @param groups groups the event belongs to (group analytics), added to the context's.
     * @param timestamp when it happened, if not now.
     * @param flags flags evaluated with [evaluateFlags]: the event carries the ones read from them
     *   (`$feature/<key>`, `$active_feature_flags`), without another evaluation.
     */
    public fun capture(
        event: String,
        properties: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
        groups: Map<String, String> = emptyMap(),
        timestamp: Instant? = null,
        flags: PostHogFlags? = null,
    ) {
        capture(event, properties.withExplicitPerson(distinctId), distinctId, threadContextProperties(), groups, timestamp) { id ->
            flags?.takeIf { it.distinctId == id }?.accessed()
        }
    }

    internal fun capture(
        event: String,
        properties: Map<String, Any?>,
        distinctId: String?,
        contextProperties: Map<String, Any>,
        groups: Map<String, String> = emptyMap(),
        timestamp: Instant? = null,
        userProperties: Map<String, Any> = emptyMap(),
        userPropertiesSetOnce: Map<String, Any> = emptyMap(),
        flagsFor: (String?) -> PostHogFeatureFlagEvaluations?,
    ) {
        val client = sdk ?: return
        if (optedOut) return
        val id = distinctId ?: contextDistinctId()
        if (id == null && !config.captureWithoutDistinctId) return
        val merged =
            buildMap {
                putAll(registered)
                putAll(contextProperties)
                putAll(properties.withoutNulls())
                if (groups.isNotEmpty()) put(GROUPS, contextGroups(contextProperties) + groups)
            }
        val options =
            PostHogCaptureOptions
                .builder()
                .properties(merged)
                .apply {
                    if (userProperties.isNotEmpty()) userProperties(userProperties)
                    if (userPropertiesSetOnce.isNotEmpty()) userPropertiesSetOnce(userPropertiesSetOnce)
                    timestamp?.let { timestamp(it) }
                    flagsFor(id)?.let { flags(it) }
                }.build()
        if (id == null) client.capture(event, options) else client.capture(id, event, options)
    }

    /**
     * Captures a `$pageview` of [url] (`$current_url`), for pages posthog-js does not see, e.g.
     * pages for clients without JavaScript. In a call, `call.posthog.capturePageview()` takes the
     * URL from the request.
     */
    public fun capturePageview(
        url: String,
        properties: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
    ) {
        capture(PAGEVIEW, properties + (CURRENT_URL to url), distinctId)
    }

    /** Sets person properties of [distinctId] (`$set`, and `$set_once` for [propertiesSetOnce]) with an `$identify` event. */
    public fun identify(
        distinctId: String,
        properties: Map<String, Any?> = emptyMap(),
        propertiesSetOnce: Map<String, Any?> = emptyMap(),
    ) {
        if (optedOut) return
        sdk?.identify(distinctId, properties.withoutNulls(), propertiesSetOnce.withoutNulls())
    }

    /**
     * Sets person properties of [distinctId], or of the current context's person, with a `$set`
     * event: [properties] as `$set`, [propertiesSetOnce] as `$set_once`. Nothing happens without a
     * distinct id.
     */
    public fun setPersonProperties(
        properties: Map<String, Any?> = emptyMap(),
        propertiesSetOnce: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
    ) {
        setPersonProperties(properties, propertiesSetOnce, distinctId, threadContextProperties())
    }

    internal fun setPersonProperties(
        properties: Map<String, Any?>,
        propertiesSetOnce: Map<String, Any?>,
        distinctId: String?,
        contextProperties: Map<String, Any>,
    ) {
        val id = distinctId ?: contextDistinctId() ?: return
        capture(
            SET,
            emptyMap(),
            id,
            contextProperties,
            userProperties = properties.withoutNulls(),
            userPropertiesSetOnce = propertiesSetOnce.withoutNulls(),
        ) { null }
    }

    /** Removes the person properties [keys] of [distinctId], or of the current context's person (`$unset`). */
    public fun unsetPersonProperties(
        keys: Collection<String>,
        distinctId: String? = null,
    ) {
        unsetPersonProperties(keys, distinctId, threadContextProperties())
    }

    internal fun unsetPersonProperties(
        keys: Collection<String>,
        distinctId: String?,
        contextProperties: Map<String, Any>,
    ) {
        val id = distinctId ?: contextDistinctId() ?: return
        if (keys.isEmpty()) return
        capture(SET, mapOf(UNSET to keys.toList()), id, contextProperties) { null }
    }

    /** Merges [alias] into [distinctId]: both ids then belong to the same person. */
    public fun alias(
        distinctId: String,
        alias: String,
    ) {
        if (optedOut) return
        sdk?.alias(distinctId, alias)
    }

    /**
     * Creates or updates the group [groupKey] of [groupType] (group analytics) with its
     * [properties] (`$groupidentify`); `name` is the name PostHog shows. The event is captured for
     * [distinctId], else the current context's, else `<groupType>_<groupKey>` as in posthog-node.
     * To have events carry a group, pass `groups` to [capture] or use `call.posthog.group`.
     */
    public fun groupIdentify(
        groupType: String,
        groupKey: String,
        properties: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
    ) {
        if (optedOut) return
        val id = distinctId ?: contextDistinctId() ?: "${groupType}_$groupKey"
        sdk?.group(id, groupType, groupKey, properties.withoutNulls())
    }

    /** Reports [error] to error tracking, for [distinctId] or the current context's. */
    public fun captureException(
        error: Throwable,
        properties: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
    ) {
        captureException(error, properties.withExplicitPerson(distinctId), distinctId, threadContextProperties()) { null }
    }

    internal fun captureException(
        error: Throwable,
        properties: Map<String, Any?>,
        distinctId: String?,
        contextProperties: Map<String, Any>,
        flagsFor: (String?) -> PostHogFeatureFlagEvaluations?,
    ) {
        val client = sdk ?: return
        if (optedOut) return
        val id = distinctId ?: contextDistinctId()
        val merged = registered + contextProperties + properties.withoutNulls()
        val options =
            PostHogCaptureOptions
                .builder()
                .properties(merged)
                .apply { flagsFor(id)?.let { flags(it) } }
                .build()
        if (id == null) client.captureException(error, options) else client.captureException(error, id, options)
    }

    /**
     * Records that [flagKey]'s feature was shown (`$feature_view`), as posthog-js' enriched flag
     * analytics: PostHog's flag page then shows its views.
     */
    public fun captureFeatureView(
        flagKey: String,
        distinctId: String? = null,
    ) {
        capture(FEATURE_VIEW, mapOf(FEATURE_FLAG to flagKey), distinctId)
    }

    /**
     * Records an interaction with [flagKey]'s feature (`$feature_interaction`), and sets the person
     * property `$feature_interaction/<flagKey>`, as posthog-js' enriched flag analytics.
     */
    public fun captureFeatureInteraction(
        flagKey: String,
        distinctId: String? = null,
    ) {
        captureFeatureInteraction(flagKey, distinctId, threadContextProperties())
    }

    internal fun captureFeatureInteraction(
        flagKey: String,
        distinctId: String?,
        contextProperties: Map<String, Any>,
    ) {
        capture(
            FEATURE_INTERACTION,
            mapOf(FEATURE_FLAG to flagKey),
            distinctId,
            contextProperties,
            userProperties = mapOf("$FEATURE_INTERACTION/$flagKey" to true),
        ) { null }
    }

    /**
     * Evaluates the flags of [distinctId], or of the current context's person, once, for work
     * outside a call (consumers, jobs); in a call, `call.posthog.flags()` does it for the call. The
     * context's groups are evaluated with; [configure] adds person properties, group properties,
     * `flagKeys` or `onlyEvaluateLocally`. Without a distinct id, while PostHog is off or opted out,
     * or if the evaluation fails, every flag is off. Pass the result to [capture] (`flags`) to have
     * an event carry the flags read from it.
     */
    public suspend fun evaluateFlags(
        distinctId: String? = null,
        configure: PostHogEvaluateFlagsOptions.Builder.() -> Unit = {},
    ): PostHogFlags {
        val id = distinctId ?: PostHogContext.current()?.distinctId ?: contextDistinctId()
        val groups = PostHogContext.current()?.groups.orEmpty()
        return evaluate(id) {
            if (groups.isNotEmpty()) groups(groups)
            configure()
        }
    }

    internal suspend fun evaluate(
        distinctId: String?,
        options: PostHogEvaluateFlagsOptions.Builder.() -> Unit,
    ): PostHogFlags {
        val client = sdk ?: return PostHogFlags.NONE
        if (optedOut || distinctId == null) return PostHogFlags.NONE
        return try {
            val built =
                PostHogEvaluateFlagsOptions
                    .builder()
                    // The server's address says nothing about where the person is.
                    .disableGeoip(true)
                    .apply(options)
                    .build()
            PostHogFlags(kotlinx.coroutines.withContext(Dispatchers.IO) { client.evaluateFlags(distinctId, built) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not evaluate feature flags; every flag is off", e)
            PostHogFlags.NONE
        }
    }

    /**
     * Every flag of [distinctId], or of the current context's person, with its value and payload,
     * without recording an exposure, as posthog-js' `getAllFeatureFlags`: for a dashboard of a
     * person's flags, or to hand flags on. Reading a value here counts as no exposure; read the
     * flags you act on with [evaluateFlags] or `call.posthog`. Empty without a distinct id, while
     * PostHog is off or opted out, or if the evaluation fails.
     */
    public suspend fun getAllFeatureFlags(
        distinctId: String? = null,
        configure: PostHogEvaluateFlagsOptions.Builder.() -> Unit = {},
    ): Map<String, PostHogFlagResult> {
        val id = distinctId ?: PostHogContext.current()?.distinctId ?: contextDistinctId()
        val groups = PostHogContext.current()?.groups.orEmpty()
        val options: PostHogEvaluateFlagsOptions.Builder.() -> Unit = {
            if (groups.isNotEmpty()) groups(groups)
            configure()
        }
        // The snapshot names the flags; reading their keys records nothing.
        val keys = evaluate(id, options).keys
        return exposureFreeResults(id ?: return emptyMap(), keys, PostHogEvaluateFlagsOptions.builder().apply(options).build())
            .mapValues { (key, result) -> PostHogFlagResult(key, result.enabled, result.variant, result.payload?.toString()) }
    }

    // The snapshot records an exposure on every read of a value; the single-flag call can skip it.
    // Locally evaluated with a secretKey; otherwise the SDK's flag cache keeps it to few requests.
    @Suppress("DEPRECATION")
    internal suspend fun exposureFreeResults(
        distinctId: String,
        flagKeys: Collection<String>,
        context: PostHogEvaluateFlagsOptions,
    ): Map<String, FeatureFlagResult> {
        val client = sdk ?: return emptyMap()
        if (optedOut || flagKeys.isEmpty()) return emptyMap()
        val options =
            PostHogFeatureFlagResultOptions
                .builder()
                .groups(context.groups.orEmpty())
                .personProperties(context.personProperties.orEmpty())
                .groupProperties(context.groupProperties.orEmpty())
                .sendFeatureFlagEvent(false)
                .build()
        return try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                flagKeys
                    .distinct()
                    .mapNotNull { key -> client.getFeatureFlagResult(distinctId, key, options)?.let { key to it } }
                    .toMap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not evaluate feature flags without an exposure; every flag is off", e)
            emptyMap()
        }
    }

    /**
     * The link to [sessionId]'s session replay in PostHog, as posthog-js' `get_session_replay_url`;
     * null while PostHog is off. In a call, `call.posthog.sessionReplayUrl`.
     */
    public fun sessionReplayUrl(sessionId: String): String? {
        val token = projectToken ?: return null
        return "${uiHost.trimEnd('/')}/project/$token/replay/$sessionId"
    }

    /** Loads the flag definitions for local evaluation again now, instead of at the next poll. */
    public fun reloadFeatureFlags() {
        sdk?.reloadFeatureFlags()
    }

    /**
     * Runs [block] in [context], bound to this client: events captured inside carry its distinct
     * id, session id, properties and groups, also after suspensions and on other threads, and spans
     * started inside get its ids if the official SDK's `PostHogContextSpanProcessor` for this client
     * is registered. For consumers and jobs: `application.posthog.withContext(PostHogContext.fromHeaders(headers)) { … }`.
     */
    public suspend fun <T> withContext(
        context: PostHogContext,
        block: suspend CoroutineScope.() -> T,
    ): T = kotlinx.coroutines.withContext(context.ownedBy(sdk), block)

    /**
     * Runs [block] in a context of [distinctId], [sessionId], [properties] and [groups]; see the
     * other overload. Inside another context (a call, an outer `withContext`) it inherits what it
     * does not set: the distinct id, the session id, and the properties and groups it adds to, as
     * posthog-node's contexts. [fresh] starts from nothing instead.
     */
    public suspend fun <T> withContext(
        distinctId: String?,
        sessionId: String? = null,
        properties: Map<String, Any?> = emptyMap(),
        groups: Map<String, String> = emptyMap(),
        fresh: Boolean = false,
        block: suspend CoroutineScope.() -> T,
    ): T {
        val parent = if (fresh) null else currentCoroutineContext()[PostHogContext]
        val context =
            if (parent == null) {
                PostHogContext(distinctId, sessionId, properties, groups)
            } else {
                val inherited =
                    // A person named here is a known one, whatever the call thought of its own.
                    if (distinctId != null) parent.properties - PROCESS_PERSON_PROFILE else parent.properties
                PostHogContext(
                    distinctId = distinctId ?: parent.distinctId,
                    sessionId = sessionId ?: parent.sessionId,
                    properties = inherited - GROUPS + properties,
                    groups = parent.groups + groups,
                )
            }
        return withContext(context, block)
    }

    /** Sends what is queued now instead of with the next batch. */
    public fun flush() {
        sdk?.flush()
    }

    // An explicit distinct id names a known person: with IDENTIFIED_ONLY its events get a profile.
    private fun Map<String, Any?>.withExplicitPerson(distinctId: String?): Map<String, Any?> =
        if (distinctId != null && config.personProfiles == PersonProfiles.IDENTIFIED_ONLY) this + (PROCESS_PERSON_PROFILE to true) else this

    internal val personProfiles: PersonProfiles get() = config.personProfiles

    private fun contextDistinctId(): String? =
        (PostHogContext.onThread()?.distinctId ?: PostHogRequestContext.current()?.distinctId)?.takeIf { it.isNotBlank() }

    private fun threadContextProperties(): Map<String, Any> =
        PostHogContext
            .onThread()
            ?.toRequestContextData()
            ?.properties
            .orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun contextGroups(contextProperties: Map<String, Any>): Map<String, String> {
        val groups = contextProperties[GROUPS] ?: PostHogRequestContext.current()?.properties?.get(GROUPS)
        return (groups as? Map<String, String>).orEmpty()
    }

    internal companion object {
        private val log = LoggerFactory.getLogger(PostHogClient::class.java)
        const val GROUPS = "\$groups"
        const val CURRENT_URL = "\$current_url"
        const val PAGEVIEW = "\$pageview"
        const val SET = "\$set"
        const val UNSET = "\$unset"
        const val FEATURE_VIEW = "\$feature_view"
        const val FEATURE_INTERACTION = "\$feature_interaction"
        const val FEATURE_FLAG = "feature_flag"
    }
}
