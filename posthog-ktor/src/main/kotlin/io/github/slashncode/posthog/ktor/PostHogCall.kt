package io.github.slashncode.posthog.ktor

import com.posthog.FeatureFlagResult
import com.posthog.server.PostHogEvaluateFlagsOptions
import com.posthog.server.PostHogFeatureFlagEvaluations
import com.posthog.server.PostHogFeatureFlagResultOptions
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * PostHog for one call: captures carry the call's distinct id, session id and request properties.
 * Every method is a no-op while PostHog is off.
 */
public class PostHogCall internal constructor(
    private val state: PostHogCallState?,
    private val client: PostHogClient,
) {
    /**
     * The distinct id events of this call are captured for: one set by [identify], else the
     * authenticated user's (`resolveDistinctId`), else the browser's tracing header; null if none.
     */
    public val distinctId: String? get() = state?.distinctId

    /** The browser's session id from its tracing header, if any. */
    public val sessionId: String? get() = state?.sessionId

    /** Captures [event]; an explicit [distinctId] wins over the call's. Null property values are left out. */
    public fun capture(
        event: String,
        properties: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
    ) {
        client.capture(event, properties, distinctId ?: this.distinctId, state?.properties.orEmpty()) { state?.accessedFlags(it) }
    }

    /**
     * Makes [distinctId] the distinct id of the rest of the call (e.g. right after a login, before
     * the browser knows), and sets person properties if given. Linking the browser's anonymous id
     * to it is posthog-js' `identify`.
     */
    public fun identify(
        distinctId: String,
        properties: Map<String, Any?> = emptyMap(),
        propertiesSetOnce: Map<String, Any?> = emptyMap(),
    ) {
        state?.identified = distinctId
        if (properties.isNotEmpty() || propertiesSetOnce.isNotEmpty()) {
            client.identify(distinctId, properties, propertiesSetOnce)
        }
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
     * again only if the distinct id changes (`identify`). What the evaluation needs besides the
     * distinct id comes from `flagContext`. Without a distinct id, or while PostHog is off, every
     * flag is off.
     */
    public suspend fun flags(): PostHogFlags = state?.flags() ?: PostHogFlags.NONE

    /** Whether the flag is on for the call; records an exposure. Shorthand for `flags().isEnabled(key)`. */
    public suspend fun isFeatureEnabled(key: String): Boolean = flags().isEnabled(key)

    /** The flag's variant, else whether it is on; null if unknown. Records an exposure. Shorthand for `flags().getFlag(key)`. */
    public suspend fun getFeatureFlag(key: String): Any? = flags().getFlag(key)

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
    get() = PostHogCall(attributes.getOrNull(PostHogCallState.key), application.posthog)

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
            )

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
            evaluated?.takeIf { it.distinctId == id } ?: evaluate(id).also { evaluated = it }
        }

    /** The flags the call has read, for an event of [eventDistinctId]: a snapshot is one person's. */
    fun accessedFlags(eventDistinctId: String?): PostHogFeatureFlagEvaluations? {
        if (!config.attachAccessedFlags) return null
        val flags = evaluated ?: return null
        return if (flags.distinctId == eventDistinctId) flags.accessed() else null
    }

    private suspend fun evaluate(distinctId: String?): PostHogFlags {
        val sdk = client.sdk ?: return PostHogFlags.NONE
        if (distinctId == null) return PostHogFlags.NONE
        return try {
            val options = flagOptions()
            PostHogFlags(withContext(Dispatchers.IO) { sdk.evaluateFlags(distinctId, options) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not evaluate feature flags; the call goes on with every flag off", e)
            PostHogFlags.NONE
        }
    }

    private fun flagOptions(): PostHogEvaluateFlagsOptions =
        PostHogEvaluateFlagsOptions
            .builder()
            .apply { config.flagContext(this, call) }
            .build()

    suspend fun bootstrap(
        flagKeys: Collection<String>,
        mintDistinctId: Boolean,
    ): PostHogBootstrap {
        // While PostHog is off there is nothing to bootstrap, and no reason to name anyone.
        if (client.sdk == null) return PostHogBootstrap(null, false, emptyMap(), emptyMap())
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

    // The snapshot records an exposure on every read of a value; the single-flag call can skip it.
    // Locally evaluated with a secretKey; otherwise the SDK's flag cache keeps it to one request.
    @Suppress("DEPRECATION")
    private suspend fun exposureFreeValues(
        distinctId: String,
        flagKeys: Collection<String>,
    ): Map<String, FeatureFlagResult> {
        val sdk = client.sdk ?: return emptyMap()
        val context = flagOptions()
        val options =
            PostHogFeatureFlagResultOptions
                .builder()
                .groups(context.groups.orEmpty())
                .personProperties(context.personProperties.orEmpty())
                .groupProperties(context.groupProperties.orEmpty())
                .sendFeatureFlagEvent(false)
                .build()
        return try {
            withContext(Dispatchers.IO) {
                flagKeys
                    .distinct()
                    .mapNotNull { key -> sdk.getFeatureFlagResult(distinctId, key, options)?.takeIf { it.enabled }?.let { key to it } }
                    .toMap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not evaluate feature flags for posthog-js; it starts with every flag off", e)
            emptyMap()
        }
    }

    private fun cookieDistinctId(): String? {
        val token = config.projectToken ?: return null
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
