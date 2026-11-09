package io.github.slashncode.posthog.ktor

import com.posthog.server.PostHogCaptureOptions
import com.posthog.server.PostHogFeatureFlagEvaluations
import com.posthog.server.PostHogInterface
import com.posthog.server.PostHogRequestContext
import kotlinx.coroutines.CoroutineScope

/**
 * The application's PostHog client. Inside a call, or a [PostHogContext], captures without an
 * explicit distinct id use the context's. Every call is a no-op while PostHog is off (no project
 * token), so code that captures needs no checks of its own.
 */
public class PostHogClient internal constructor(
    /** The official SDK's client, for everything this library does not wrap; null while PostHog is off. */
    public val sdk: PostHogInterface?,
    private val captureWithoutDistinctId: Boolean,
    internal val host: String = POSTHOG_US_HOST,
) {
    /** Whether events actually go to PostHog. */
    public val isEnabled: Boolean get() = sdk != null

    /**
     * Captures [event] for [distinctId], or for the current context's distinct id. Without either it
     * is sent without a person profile, or dropped if `captureWithoutDistinctId` is off. Null
     * property values are left out.
     */
    public fun capture(
        event: String,
        properties: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
    ) {
        capture(event, properties, distinctId, emptyMap()) { null }
    }

    internal fun capture(
        event: String,
        properties: Map<String, Any?>,
        distinctId: String?,
        contextProperties: Map<String, Any>,
        flagsFor: (String?) -> PostHogFeatureFlagEvaluations?,
    ) {
        val client = sdk ?: return
        val id = distinctId ?: contextDistinctId()
        if (id == null && !captureWithoutDistinctId) return
        val options = options(contextProperties + properties.withoutNulls(), flagsFor(id))
        if (id == null) client.capture(event, options) else client.capture(id, event, options)
    }

    /** Sets person properties of [distinctId] (`$set`, and `$set_once` for [propertiesSetOnce]). */
    public fun identify(
        distinctId: String,
        properties: Map<String, Any?> = emptyMap(),
        propertiesSetOnce: Map<String, Any?> = emptyMap(),
    ) {
        sdk?.identify(distinctId, properties.withoutNulls(), propertiesSetOnce.withoutNulls())
    }

    /** Merges [alias] into [distinctId]: both ids then belong to the same person. */
    public fun alias(
        distinctId: String,
        alias: String,
    ) {
        sdk?.alias(distinctId, alias)
    }

    /** Associates [distinctId] with the group [groupKey] of [groupType] (group analytics), setting its properties. */
    public fun group(
        distinctId: String,
        groupType: String,
        groupKey: String,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        sdk?.group(distinctId, groupType, groupKey, properties.withoutNulls())
    }

    /** Reports [error] to error tracking, for [distinctId] or the current context's. */
    public fun captureException(
        error: Throwable,
        properties: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
    ) {
        captureException(error, properties, distinctId, emptyMap()) { null }
    }

    internal fun captureException(
        error: Throwable,
        properties: Map<String, Any?>,
        distinctId: String?,
        contextProperties: Map<String, Any>,
        flagsFor: (String?) -> PostHogFeatureFlagEvaluations?,
    ) {
        val client = sdk ?: return
        val id = distinctId ?: contextDistinctId()
        val options = options(contextProperties + properties.withoutNulls(), flagsFor(id))
        if (id == null) client.captureException(error, options) else client.captureException(error, id, options)
    }

    /**
     * Runs [block] in [context], bound to this client: events captured inside carry its distinct
     * id, session id and properties, also after suspensions and on other threads, and spans started
     * inside get its ids if the official SDK's `PostHogContextSpanProcessor` for this client is
     * registered. For consumers and jobs: `application.posthog.withContext(PostHogContext.fromHeaders(headers)) { … }`.
     */
    public suspend fun <T> withContext(
        context: PostHogContext,
        block: suspend CoroutineScope.() -> T,
    ): T = kotlinx.coroutines.withContext(context.ownedBy(sdk), block)

    /** Runs [block] in a context of [distinctId], [sessionId] and [properties]; see the other overload. */
    public suspend fun <T> withContext(
        distinctId: String?,
        sessionId: String? = null,
        properties: Map<String, Any?> = emptyMap(),
        block: suspend CoroutineScope.() -> T,
    ): T = withContext(PostHogContext(distinctId, sessionId, properties), block)

    /** Sends what is queued now instead of with the next batch. */
    public fun flush() {
        sdk?.flush()
    }

    private fun options(
        properties: Map<String, Any>,
        flags: PostHogFeatureFlagEvaluations?,
    ): PostHogCaptureOptions =
        PostHogCaptureOptions
            .builder()
            .properties(properties)
            .apply { if (flags != null) flags(flags) }
            .build()

    private fun contextDistinctId(): String? = PostHogRequestContext.current()?.distinctId?.takeIf { it.isNotBlank() }
}
