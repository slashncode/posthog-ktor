package io.github.slashncode.posthog.ktor

import com.posthog.FeatureFlagResult
import com.posthog.PersonProfiles
import com.posthog.server.PostHogEvaluateFlagsOptions
import com.posthog.server.PostHogFeatureFlagEvaluations
import com.posthog.server.PostHogRequestContext
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.RoutingNode
import io.ktor.server.util.url
import io.ktor.util.AttributeKey
import io.ktor.util.toMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * PostHog for one call: captures carry the call's distinct id, session id and request properties.
 * Every method is a no-op while PostHog is off.
 */
public class PostHogCall internal constructor(
    internal val state: PostHogCallState?,
    internal val client: PostHogClient,
) {
    /**
     * The distinct id events of this call are captured for: one set by [identify], else the
     * authenticated user's (`resolveDistinctId`), else the browser's tracing header; null if none.
     */
    public val distinctId: String? get() = state?.distinctId

    /** The browser's session id from its tracing header, if any. */
    public val sessionId: String? get() = state?.sessionId

    /** The link to this call's session replay in PostHog; null without a session id or while PostHog is off. */
    public val sessionReplayUrl: String? get() = sessionId?.let(client::sessionReplayUrl)

    /**
     * Captures [event]; an explicit [distinctId] wins over the call's. Null property values are left
     * out. [groups] are added to the call's (see [group]); [timestamp] is when it happened, if not now.
     */
    public fun capture(
        event: String,
        properties: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
        groups: Map<String, String> = emptyMap(),
        timestamp: Instant? = null,
    ) {
        val own =
            if (distinctId != null &&
                client.personProfiles == PersonProfiles.IDENTIFIED_ONLY
            ) {
                properties + (PROCESS_PERSON_PROFILE to true)
            } else {
                properties
            }
        client.capture(
            event,
            own,
            distinctId ?: this.distinctId,
            state?.properties.orEmpty(),
            groups,
            timestamp,
        ) { state?.accessedFlags(it) }
    }

    /**
     * Captures a `$pageview` of this call's URL, for pages posthog-js does not see (clients without
     * JavaScript, a server-rendered page before posthog-js loads is not one: it captures its own).
     */
    public fun capturePageview(properties: Map<String, Any?> = emptyMap()) {
        capture(PostHogClient.PAGEVIEW, properties)
    }

    /**
     * Makes [distinctId] the distinct id of the rest of the call (e.g. right after a login, before
     * the browser knows), and sets person properties if given; they are also used for the call's
     * flag evaluation, as posthog-js does. Linking the browser's anonymous id to it is posthog-js'
     * `identify`.
     */
    public fun identify(
        distinctId: String,
        properties: Map<String, Any?> = emptyMap(),
        propertiesSetOnce: Map<String, Any?> = emptyMap(),
    ) {
        state?.identified = distinctId
        state?.addPersonPropertiesForFlags(properties)
        if (properties.isNotEmpty() || propertiesSetOnce.isNotEmpty()) {
            client.identify(distinctId, properties, propertiesSetOnce)
        }
    }

    /**
     * Sets person properties of the call's person with a `$set` event ([propertiesSetOnce] as
     * `$set_once`), also for the call's flag evaluation. Nothing happens without a distinct id.
     */
    public fun setPersonProperties(
        properties: Map<String, Any?> = emptyMap(),
        propertiesSetOnce: Map<String, Any?> = emptyMap(),
    ) {
        val id = distinctId ?: return
        state?.processPersons()
        state?.addPersonPropertiesForFlags(properties)
        client.setPersonProperties(properties, propertiesSetOnce, id, personContext())
    }

    /** Removes the person properties [keys] of the call's person (`$unset`). */
    public fun unsetPersonProperties(keys: Collection<String>) {
        val id = distinctId ?: return
        state?.processPersons()
        client.unsetPersonProperties(keys, id, personContext())
    }

    /** Merges [alias] into the call's distinct id: both then belong to the same person. */
    public fun alias(alias: String) {
        val id = distinctId ?: return
        state?.processPersons()
        client.alias(id, alias)
    }

    /**
     * Puts the call in the group [groupKey] of [groupType] (group analytics): every event of the
     * rest of the call carries it (`$groups`), and the call's flags are evaluated with it. Creates or
     * updates the group with [properties] (`$groupidentify`; `name` is the name PostHog shows).
     */
    public fun group(
        groupType: String,
        groupKey: String,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        state?.addGroup(groupType, groupKey)
        state?.processPersons()
        client.groupIdentify(groupType, groupKey, properties, distinctId)
    }

    /**
     * Person properties the call's flags are evaluated with, on top of the ones PostHog has stored,
     * as posthog-js' `setPersonPropertiesForFlags`. Additive; the next read evaluates again.
     */
    public fun setPersonPropertiesForFlags(properties: Map<String, Any?>) {
        state?.addPersonPropertiesForFlags(properties)
    }

    /** Forgets the person properties set for flag evaluation. */
    public fun resetPersonPropertiesForFlags() {
        state?.resetPersonPropertiesForFlags()
    }

    /**
     * Group properties the call's flags are evaluated with, by group type, as posthog-js'
     * `setGroupPropertiesForFlags`: `mapOf("company" to mapOf("plan" to "pro"))`. Additive.
     */
    public fun setGroupPropertiesForFlags(properties: Map<String, Map<String, Any?>>) {
        state?.addGroupPropertiesForFlags(properties)
    }

    /** Forgets the group properties set for flag evaluation, of [groupType] or of all groups. */
    public fun resetGroupPropertiesForFlags(groupType: String? = null) {
        state?.resetGroupPropertiesForFlags(groupType)
    }

    /** Reports [error] to error tracking with the call's ids and request properties. */
    public fun captureException(
        error: Throwable,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        client.captureException(error, properties, distinctId, state?.properties.orEmpty()) { state?.accessedFlags(it) }
    }

    /**
     * The call's feature flags for its distinct id, evaluated on first use (locally with a
     * `secretKey`, else with one request to PostHog) and kept for the rest of the call; evaluated
     * again only if what they depend on changes: the distinct id ([identify]), the call's groups
     * ([group]) or the properties set for flags. What the evaluation needs besides comes from
     * `flagContext`. Without a distinct id, or while PostHog is off or opted out, every flag is off.
     */
    public suspend fun flags(): PostHogFlags = state?.flags() ?: PostHogFlags.NONE

    /** Whether the flag is on for the call; records an exposure. Shorthand for `flags().isEnabled(key)`. */
    public suspend fun isFeatureEnabled(key: String): Boolean = flags().isEnabled(key)

    /** The flag's variant, else whether it is on; null if unknown. Records an exposure. Shorthand for `flags().getFlag(key)`. */
    public suspend fun getFeatureFlag(key: String): Any? = flags().getFlag(key)

    /** The flag's value and payload in one; null if unknown. Records an exposure. Shorthand for `flags().getFlagResult(key)`. */
    public suspend fun getFeatureFlagResult(key: String): PostHogFlagResult? = flags().getFlagResult(key)

    /**
     * Every flag of the call's person with its value and payload, without recording an exposure, as
     * posthog-js' `getAllFeatureFlags`. Evaluated with the call's flag context; empty without a
     * distinct id or while PostHog is off.
     */
    public suspend fun getAllFeatureFlags(): Map<String, PostHogFlagResult> = state?.allFlags().orEmpty()

    /** Records that [flagKey]'s feature was shown (`$feature_view`), as posthog-js' enriched flag analytics. */
    public fun captureFeatureView(flagKey: String) {
        capture(PostHogClient.FEATURE_VIEW, mapOf(PostHogClient.FEATURE_FLAG to flagKey))
    }

    /**
     * Records an interaction with [flagKey]'s feature (`$feature_interaction`) and sets the person
     * property `$feature_interaction/<flagKey>`, as posthog-js' enriched flag analytics.
     */
    public fun captureFeatureInteraction(flagKey: String) {
        client.captureFeatureInteraction(flagKey, distinctId, personContext())
    }

    // Person events create the profile even under IDENTIFIED_ONLY, as in posthog-js.
    internal fun personContext(): Map<String, Any> {
        val properties = state?.properties.orEmpty()
        return if (client.personProfiles == PersonProfiles.IDENTIFIED_ONLY) properties + (PROCESS_PERSON_PROFILE to true) else properties
    }

    /**
     * posthog-js' bootstrap for a page this call renders: the values and payloads of [flagKeys]
     * (only these: flags not meant for the browser stay on the server), evaluated without an
     * exposure.
     *
     * The distinct id is the call's (identified, resolved or from the tracing header), else the one
     * in posthog-js' cookie (`ph_<projectToken>_posthog`, read with `useTracingHeaders`), else, if
     * [mintDistinctId], a new anonymous one (a UUIDv7, as posthog-js makes them) that the rest of
     * the call captures with too. Without one, no flags are evaluated: mint one only for visitors
     * PostHog may track.
     */
    public suspend fun bootstrap(
        flagKeys: Collection<String>,
        mintDistinctId: Boolean = false,
    ): PostHogBootstrap = state?.bootstrap(flagKeys, mintDistinctId) ?: PostHogBootstrap(null, false, emptyMap(), emptyMap())
}

/** PostHog for this call; the [PostHog] plugin must be installed. */
public val ApplicationCall.posthog: PostHogCall
    get() {
        val client = application.posthog
        if (attributes.contains(blockedKey)) return PostHogCall(null, client.off)
        return PostHogCall(attributes.getOrNull(PostHogCallState.key), client)
    }

/** What the plugin knows about a call: its ids, its request properties, how it ended. */
internal class PostHogCallState(
    private val call: ApplicationCall,
    private val config: PostHogPluginConfig,
    private val client: PostHogClient,
) {
    private val headers =
        if (config.useTracingHeaders) {
            PostHogRequestContext.fromHeaders(call.request.headers.toMap(), true)
        } else {
            null
        }
    private var resolved: String? = null

    var identified: String? = null
    var route: RoutingNode? = null
    var failure: Throwable? = null

    // From posthog-js' cookie or minted by bootstrap: the id of last resort.
    @Volatile private var bootstrapped: String? = null
    private val flagsLock = Mutex()

    @Volatile private var evaluated: PostHogFlags? = null

    // What flags are evaluated with besides the distinct id; a change counts up flagInputs.
    private val callGroups = ConcurrentHashMap<String, String>()
    private val flagPersonProperties = ConcurrentHashMap<String, Any>()
    private val flagGroupProperties = ConcurrentHashMap<String, Map<String, Any>>()
    private val flagInputs = AtomicInteger()

    @Volatile private var evaluatedInputs = -1

    // Set by person operations (setPersonProperties, group, alias): the call's person gets a profile.
    @Volatile private var personProcessing = false

    // What does not change during the call, built once.
    private val requestProperties: Map<String, Any> by lazy {
        val request = call.request
        buildMap {
            put("\$current_url", call.url())
            put("\$request_method", request.httpMethod.value)
            put("\$request_path", request.path())
            request.headers[HttpHeaders.UserAgent]?.let { put("\$user_agent", it) }
            if (config.captureIp) put("\$ip", request.origin.remoteAddress)
        }
    }

    val sessionId: String? = headers?.sessionId

    val distinctId: String?
        get() = identified ?: resolvedDistinctId() ?: headers?.distinctId ?: bootstrapped

    private val isIdentified: Boolean get() = identified != null || resolvedDistinctId() != null

    // Resolved once known: before authentication has run, the resolver has nothing to go by.
    private fun resolvedDistinctId(): String? {
        resolved?.let { return it }
        resolved =
            try {
                config.distinctIdResolver(call)?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                log.debug("resolveDistinctId failed; the call keeps its tracing-header identity", e)
                null
            }
        return resolved
    }

    val properties: Map<String, Any>
        get() =
            config.propertiesMap(
                buildMap {
                    putAll(requestProperties)
                    route?.let { put("\$route", it.template()) }
                    putAll(config.extraProperties(call).withoutNulls())
                },
            ) +
                buildMap {
                    if (callGroups.isNotEmpty()) put(PostHogClient.GROUPS, callGroups.toMap())
                    // Explicit both ways: the SDK's copy of the context may still say otherwise.
                    if (client.personProfiles != PersonProfiles.ALWAYS && distinctId != null) put(PROCESS_PERSON_PROFILE, processesPersons)
                }

    private val processesPersons: Boolean
        get() =
            when (client.personProfiles) {
                PersonProfiles.ALWAYS -> true
                PersonProfiles.NEVER -> false
                PersonProfiles.IDENTIFIED_ONLY -> isIdentified || personProcessing
            }

    fun processPersons() {
        personProcessing = true
    }

    fun addGroup(
        groupType: String,
        groupKey: String,
    ) {
        if (callGroups.put(groupType, groupKey) != groupKey) flagInputs.incrementAndGet()
    }

    fun addPersonPropertiesForFlags(properties: Map<String, Any?>) {
        val values = properties.withoutNulls()
        if (values.isEmpty()) return
        flagPersonProperties.putAll(values)
        flagInputs.incrementAndGet()
    }

    fun resetPersonPropertiesForFlags() {
        flagPersonProperties.clear()
        flagInputs.incrementAndGet()
    }

    fun addGroupPropertiesForFlags(properties: Map<String, Map<String, Any?>>) {
        properties.forEach { (groupType, values) ->
            flagGroupProperties.merge(groupType, values.withoutNulls()) { old, new -> old + new }
        }
        flagInputs.incrementAndGet()
    }

    fun resetGroupPropertiesForFlags(groupType: String?) {
        if (groupType == null) flagGroupProperties.clear() else flagGroupProperties.remove(groupType)
        flagInputs.incrementAndGet()
    }

    val context: PostHogContext =
        PostHogContext.forCall(
            distinctIdOf = { distinctId },
            sessionId = sessionId,
            propertiesOf = { properties },
            owner = client.sdk,
            logContext = config.logContext,
        )

    // Outside the call's coroutine, so with the context's properties (session id included) given explicitly.
    fun captureFailure(failure: Throwable) {
        client.captureException(failure, emptyMap(), distinctId, context.toRequestContextData().properties.orEmpty(), ::accessedFlags)
    }

    suspend fun flags(): PostHogFlags =
        flagsLock.withLock {
            val id = distinctId
            val inputs = flagInputs.get()
            evaluated?.takeIf { it.distinctId == id && evaluatedInputs == inputs }
                ?: client.evaluate(id) { callFlagContext() }.also {
                    evaluated = it
                    evaluatedInputs = inputs
                }
        }

    /** The flags the call has read, for an event of [eventDistinctId]: a snapshot is one person's. */
    fun accessedFlags(eventDistinctId: String?): PostHogFeatureFlagEvaluations? {
        if (!config.attachAccessedFlags) return null
        val flags = evaluated ?: return null
        return if (flags.distinctId == eventDistinctId) flags.accessed() else null
    }

    private fun PostHogEvaluateFlagsOptions.Builder.callFlagContext() {
        if (callGroups.isNotEmpty()) groups(callGroups.toMap())
        if (flagPersonProperties.isNotEmpty()) personProperties(flagPersonProperties.toMap())
        if (flagGroupProperties.isNotEmpty()) groupProperties(flagGroupProperties.toMap())
        config.flagContext(this, call)
    }

    private fun flagOptions(): PostHogEvaluateFlagsOptions =
        PostHogEvaluateFlagsOptions
            .builder()
            .apply { callFlagContext() }
            .build()

    suspend fun bootstrap(
        flagKeys: Collection<String>,
        mintDistinctId: Boolean,
    ): PostHogBootstrap {
        // While PostHog is off there is nothing to bootstrap, and no reason to name anyone.
        if (!client.isEnabled) return PostHogBootstrap(null, false, emptyMap(), emptyMap())
        if (distinctId == null) {
            bootstrapped = cookieDistinctId() ?: if (mintDistinctId) mintedDistinctId() else null
        }
        val id = distinctId ?: return PostHogBootstrap(null, false, emptyMap(), emptyMap())
        val values = exposureFreeValues(id, flagKeys)
        return PostHogBootstrap(
            distinctId = id,
            isIdentified = isIdentified,
            featureFlags = values.mapValues { (_, result) -> result.variant ?: true },
            featureFlagPayloads = values.mapNotNull { (key, result) -> result.payload?.let { key to it.toString() } }.toMap(),
        )
    }

    private suspend fun exposureFreeValues(
        distinctId: String,
        flagKeys: Collection<String>,
    ): Map<String, FeatureFlagResult> = client.exposureFreeResults(distinctId, flagKeys, flagOptions()).filterValues { it.enabled }

    suspend fun allFlags(): Map<String, PostHogFlagResult> {
        val id = distinctId ?: return emptyMap()
        val keys = flags().keys
        return client
            .exposureFreeResults(id, keys, flagOptions())
            .mapValues { (key, result) -> PostHogFlagResult(key, result.enabled, result.variant, result.payload?.toString()) }
    }

    private fun cookieDistinctId(): String? {
        val token = client.projectToken ?: return null
        if (!config.useTracingHeaders) return null
        val cookie = call.request.cookies["ph_${token}_posthog"] ?: return null
        return try {
            (Json.parseToJsonElement(cookie).jsonObject["distinct_id"] as? JsonPrimitive)
                ?.contentOrNull
                // Sanitized like the tracing header it stands in for.
                ?.let { PostHogRequestContext.fromHeaders(mapOf(DISTINCT_ID_HEADER to it)).distinctId }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        val key = AttributeKey<PostHogCallState>("PostHogCallState")
        private val log = LoggerFactory.getLogger(PostHogCallState::class.java)

        @OptIn(ExperimentalUuidApi::class)
        private fun mintedDistinctId(): String = Uuid.generateV7().toString()
    }
}

/**
 * The route's path template, e.g. `/orders/{id}`: the path segments of the route and its parents
 * without the other selectors (`(method:GET)`, `(authenticate …)`).
 */
internal fun RoutingNode.template(): String {
    val path = toString().replace(Regex("/\\([^)]*\\)"), "").replace(Regex("/+"), "/")
    return if (path.isEmpty()) "/" else path
}
