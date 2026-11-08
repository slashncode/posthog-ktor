package io.github.slashncode.posthog.ktor

import com.posthog.server.PostHogCaptureOptions
import com.posthog.server.PostHogInterface
import com.posthog.server.PostHogRequestContext

/**
 * The application's PostHog client. Inside a call, or a [PostHogContext], captures without an
 * explicit distinct id use the context's. Every call is a no-op while PostHog is off (no project
 * token), so code that captures needs no checks of its own.
 */
public class PostHogClient internal constructor(
    /** The official SDK's client, for everything this library does not wrap; null while PostHog is off. */
    public val sdk: PostHogInterface?,
    private val captureWithoutDistinctId: Boolean,
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
        capture(event, properties, distinctId, emptyMap())
    }

    internal fun capture(
        event: String,
        properties: Map<String, Any?>,
        distinctId: String?,
        contextProperties: Map<String, Any>,
    ) {
        val client = sdk ?: return
        val id = distinctId ?: contextDistinctId()
        if (id == null && !captureWithoutDistinctId) return
        val options = PostHogCaptureOptions.builder()
        (contextProperties + properties.withoutNulls()).forEach { (key, value) -> options.property(key, value) }
        if (id == null) client.capture(event, options.build()) else client.capture(id, event, options.build())
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
        captureException(error, properties, distinctId, emptyMap())
    }

    internal fun captureException(
        error: Throwable,
        properties: Map<String, Any?>,
        distinctId: String?,
        contextProperties: Map<String, Any>,
    ) {
        val client = sdk ?: return
        val id = distinctId ?: contextDistinctId()
        val merged = contextProperties + properties.withoutNulls()
        if (id == null) client.captureException(error, merged) else client.captureException(error, id, merged)
    }

    /** Sends what is queued now instead of with the next batch. */
    public fun flush() {
        sdk?.flush()
    }

    private fun contextDistinctId(): String? = PostHogRequestContext.current()?.distinctId?.takeIf { it.isNotBlank() }
}
