package io.github.slashncode.posthog.ktor

import com.posthog.server.PostHogCaptureOptions
import com.posthog.server.PostHogInterface

/**
 * The application's PostHog client. Every call is a no-op while PostHog is off (no project
 * token), so code that captures needs no checks of its own.
 */
public class PostHogClient internal constructor(
    /** The official SDK's client, for everything this library does not wrap; null while PostHog is off. */
    public val sdk: PostHogInterface?,
) {
    /** Whether events actually go to PostHog. */
    public val isEnabled: Boolean get() = sdk != null

    /** Captures [event] for [distinctId]; null property values are left out. */
    public fun capture(
        event: String,
        distinctId: String,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        val client = sdk ?: return
        val options = PostHogCaptureOptions.builder()
        properties.forEach { (key, value) -> if (value != null) options.property(key, value) }
        client.capture(distinctId, event, options.build())
    }

    /** Sends what is queued now instead of with the next batch. */
    public fun flush() {
        sdk?.flush()
    }
}
