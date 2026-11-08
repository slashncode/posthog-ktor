package io.github.slashncode.posthog.ktor

import io.ktor.server.plugins.calllogging.CallLoggingConfig

/**
 * Puts [MDC_DISTINCT_ID] and [MDC_SESSION_ID] on CallLogging's request line too. CallLogging writes
 * that line outside the call's coroutine, where the [PostHog] plugin's MDC does not reach.
 *
 * ```kotlin
 * install(CallLogging) { posthogContext() }
 * ```
 */
public fun CallLoggingConfig.posthogContext() {
    mdc(MDC_DISTINCT_ID) { call -> call.posthog.distinctId }
    mdc(MDC_SESSION_ID) { call -> call.posthog.sessionId }
}
