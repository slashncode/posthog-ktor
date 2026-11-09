package io.github.slashncode.posthog.ktor

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.isHandled
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.get

/**
 * Routes that exist only while the feature flag [key] is on for the call, or, with [variant], only
 * for that variant; otherwise [onDisabled] answers (404 by default). The flag is read after
 * authentication, so `resolveDistinctId` and `flagContext` see the principal, and its exposure is
 * recorded only for calls that reach these routes.
 *
 * ```kotlin
 * featureFlag("new-checkout") {
 *     post("/checkout") { … }
 * }
 * featureFlag("checkout-copy", variant = "calm", onDisabled = { it.respondRedirect("/checkout") }) {
 *     get("/checkout/calm") { … }
 * }
 * ```
 */
public fun Route.featureFlag(
    key: String,
    variant: String? = null,
    onDisabled: suspend (ApplicationCall) -> Unit = { it.respond(HttpStatusCode.NotFound) },
    build: Route.() -> Unit,
): Route {
    val route = createChild(FeatureFlagRouteSelector(key, variant))
    // The Call phase of a parent route runs after every plugin of the call (Authentication too) and
    // before the handlers of its children. A call a plugin has answered (a 401 challenge) goes on
    // through the pipeline; like the handlers, the gate leaves it alone.
    (route as RoutingNode).intercept(ApplicationCallPipeline.Call) {
        if (context.isHandled) return@intercept
        val flags = context.posthog.flags()
        val on = if (variant == null) flags.isEnabled(key) else flags.getFlag(key) == variant
        if (!on) {
            onDisabled(context)
            finish()
        }
    }
    route.build()
    return route
}

/**
 * A GET route at [path] answering posthog-js' bootstrap for the caller (`featureFlags` and
 * `featureFlagPayloads` of [flagKeys]), evaluated without an exposure and never cached
 * (`Cache-Control: no-store`). A single-page app fetches it before its first render and passes it
 * to `posthog.init(…, { bootstrap })`, so experiments show their variant without flicker; posthog-js
 * records the exposure when the page reads the flag.
 *
 * The distinct id is the call's (from posthog-js' tracing header or its cookie, or the
 * authenticated user); without one the answer is empty: no tracking, no flags.
 */
public fun Route.posthogFlags(
    path: String = "flags",
    flagKeys: Collection<String>,
): Route {
    val keys = flagKeys.toList()
    return get(path) {
        val bootstrap = call.posthog.bootstrap(keys)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(bootstrap.json(withIdentity = false).toString(), ContentType.Application.Json)
    }
}

private class FeatureFlagRouteSelector(
    private val key: String,
    private val variant: String?,
) : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(featureFlag $key${variant?.let { "=$it" }.orEmpty()})"
}
