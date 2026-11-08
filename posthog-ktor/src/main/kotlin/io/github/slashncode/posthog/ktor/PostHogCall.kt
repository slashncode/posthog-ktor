package io.github.slashncode.posthog.ktor

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
import org.slf4j.LoggerFactory

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
        client.capture(event, properties, distinctId ?: this.distinctId, state?.properties.orEmpty())
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
        client.captureException(error, properties, distinctId, state?.properties.orEmpty())
    }
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
        get() = identified ?: resolvedDistinctId() ?: headers?.distinctId

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
        client.captureException(failure, emptyMap(), distinctId, context.toRequestContextData().properties.orEmpty())
    }

    companion object {
        val key = AttributeKey<PostHogCallState>("PostHogCallState")
        private val log = LoggerFactory.getLogger(PostHogCallState::class.java)
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
