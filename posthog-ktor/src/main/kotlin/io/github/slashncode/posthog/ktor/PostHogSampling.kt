package io.github.slashncode.posthog.ktor

import com.posthog.PostHogEvent
import kotlin.random.Random

// Sampling hooks for PostHogPluginConfig.beforeSend, after posthog-js' customizations.

/**
 * A [PostHogPluginConfig.beforeSend] hook that keeps [percent] (0.0 to 1.0) of the events named in
 * [events], chosen at random; other events pass untouched.
 *
 * ```kotlin
 * beforeSend(sampleByEvent(setOf("search_performed"), 0.1))
 * ```
 */
public fun sampleByEvent(
    events: Collection<String>,
    percent: Double,
): (PostHogEvent) -> PostHogEvent? {
    requirePercent(percent)
    val names = events.toSet()
    return { event -> if (event.event !in names || Random.nextDouble() < percent) event else null }
}

/**
 * A [PostHogPluginConfig.beforeSend] hook that keeps the events of [percent] (0.0 to 1.0) of all
 * persons, by their distinct id: a person's events are all kept or all dropped, on every server.
 */
public fun sampleByDistinctId(percent: Double): (PostHogEvent) -> PostHogEvent? {
    requirePercent(percent)
    return { event -> if (inSample(event.distinctId, percent)) event else null }
}

/**
 * A [PostHogPluginConfig.beforeSend] hook that keeps the events of [percent] (0.0 to 1.0) of all
 * sessions, by `$session_id`: a session's events are all kept or all dropped. Events without a
 * session pass.
 */
public fun sampleBySessionId(percent: Double): (PostHogEvent) -> PostHogEvent? {
    requirePercent(percent)
    return { event ->
        val sessionId = event.properties?.get("\$session_id") as? String
        if (sessionId == null || inSample(sessionId, percent)) event else null
    }
}

private fun requirePercent(percent: Double) {
    require(percent in 0.0..1.0) { "percent must be between 0.0 and 1.0, was $percent" }
}

// String.hashCode is the same on every JVM, so the sample is too.
private fun inSample(
    id: String,
    percent: Double,
): Boolean = (id.hashCode().toLong() and 0xffffffffL) % 10_000 < percent * 10_000
