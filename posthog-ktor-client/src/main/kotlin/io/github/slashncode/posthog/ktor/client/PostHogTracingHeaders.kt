package io.github.slashncode.posthog.ktor.client

import com.posthog.server.PostHogRequestContext
import io.github.slashncode.posthog.ktor.DISTINCT_ID_HEADER
import io.github.slashncode.posthog.ktor.PostHogContext
import io.github.slashncode.posthog.ktor.SESSION_ID_HEADER
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.URLBuilder

/** Configuration of [PostHogTracingHeaders]. */
public class PostHogTracingHeadersConfig internal constructor() {
    /**
     * The hosts the ids are sent to: exact names (`orders`, `orders.internal`), or `*.internal` for
     * every subdomain of `internal`. Empty, nothing is sent: a distinct id says who a person is, so
     * it goes only to services that are meant to know.
     */
    public val hosts: MutableSet<String> = mutableSetOf()
}

/**
 * Passes the current PostHog distinct id and session id on to the services this client calls, as
 * the tracing headers posthog-js sends (`X-PostHog-Distinct-Id`, `X-PostHog-Session-Id`), so their
 * events, logs and errors belong to the same person and session replay. The ids come from the
 * calling coroutine's [PostHogContext] (a call of a Ktor server with the PostHog plugin,
 * `posthog.withContext { … }`), else from the official SDK's request context. Only to
 * [PostHogTracingHeadersConfig.hosts]; headers a request sets itself are kept.
 *
 * ```kotlin
 * val orders = HttpClient(CIO) {
 *     install(PostHogTracingHeaders) { hosts += "orders.internal" }
 * }
 * ```
 */
public val PostHogTracingHeaders: ClientPlugin<PostHogTracingHeadersConfig> =
    createClientPlugin("PostHogTracingHeaders", ::PostHogTracingHeadersConfig) {
        val exact =
            pluginConfig.hosts
                .filterNot { it.startsWith("*.") }
                .map { it.lowercase() }
                .toSet()
        val suffixes = pluginConfig.hosts.filter { it.startsWith("*.") }.map { it.substring(1).lowercase() }

        fun allowed(url: URLBuilder): Boolean {
            val host = url.host.lowercase()
            return host in exact || suffixes.any { host.endsWith(it) }
        }

        onRequest { request, _ ->
            if (!allowed(request.url)) return@onRequest
            val context = PostHogContext.current()
            val distinctId = context?.distinctId ?: PostHogRequestContext.current()?.distinctId
            val sessionId = context?.sessionId ?: PostHogRequestContext.current()?.sessionId
            if (distinctId != null && !request.headers.contains(DISTINCT_ID_HEADER)) {
                request.headers.append(DISTINCT_ID_HEADER, distinctId)
            }
            if (sessionId != null && !request.headers.contains(SESSION_ID_HEADER)) {
                request.headers.append(SESSION_ID_HEADER, sessionId)
            }
        }
    }
