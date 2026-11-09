package io.github.slashncode.posthog.ktor.sample

import io.github.slashncode.posthog.ktor.PostHog
import io.github.slashncode.posthog.ktor.PostHogBootstrap
import io.github.slashncode.posthog.ktor.featureFlag
import io.github.slashncode.posthog.ktor.posthog
import io.github.slashncode.posthog.ktor.posthogContext
import io.github.slashncode.posthog.ktor.posthogFlags
import io.github.slashncode.posthog.ktor.posthogProxy
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.unsafe

fun main(args: Array<String>) = EngineMain.main(args)

/** The experiment on the checkout button: `control` or `calm`. */
const val CHECKOUT_COPY = "checkout-copy"

/** Routes behind this flag exist only for the visitors it is on for. */
const val EXPRESS_CHECKOUT = "express-checkout"

/**
 * A one-page shop that orders nothing, with PostHog on the server and in the browser:
 * posthog-js loads through the app's own `/ingest` proxy, starts with the server's flags
 * (bootstrap), and server and browser capture for the same person.
 *
 * The sample treats every visitor as having agreed to analytics. A real shop asks first and mints
 * no distinct id before the answer.
 */
fun Application.module() {
    install(PostHog) {
        // A real app resolves its signed-in user here; the sample keeps one in a cookie.
        resolveDistinctId { it.request.cookies["user"] }
    }
    install(CallLogging) { posthogContext() }
    val projectToken = environment.config.propertyOrNull("posthog.projectToken")?.getString()
    val host = environment.config.propertyOrNull("posthog.host")?.getString()

    routing {
        posthogProxy("/ingest")
        posthogFlags("/api/flags", listOf(CHECKOUT_COPY))

        get("/") {
            // First the bootstrap: it names a first-time visitor (minted id) for the rest of the call.
            val bootstrap = call.posthog.bootstrap(listOf(CHECKOUT_COPY), mintDistinctId = true)
            // A server-rendered variant: reading the flag here records the exposure.
            val calm = call.posthog.getFeatureFlag(CHECKOUT_COPY) == "calm"
            call.respondHtml { shop(projectToken, host, bootstrap, calm) }
        }
        post("/checkout") {
            call.posthog.capture("order_placed", mapOf("total" to 12.5, "items" to 1))
            call.respondRedirect("/thanks")
        }
        featureFlag(EXPRESS_CHECKOUT) {
            post("/checkout/express") {
                call.posthog.capture("order_placed", mapOf("total" to 12.5, "items" to 1, "express" to true))
                call.respondRedirect("/thanks")
            }
        }
        get("/thanks") {
            call.respondHtml { body { h1 { +"Nothing is on its way." } } }
        }
    }
}

private fun HTML.shop(
    projectToken: String?,
    host: String?,
    bootstrap: PostHogBootstrap,
    calm: Boolean,
) {
    head {
        title { +"NothingEats sample" }
        if (projectToken != null) {
            // toJson() is escaped for <script>; the token and host come from the app's own config.
            val init =
                """
                posthog.init('$projectToken', {
                    api_host: '/ingest',
                    ui_host: '${uiHost(host)}',
                    bootstrap: ${bootstrap.toJson()},
                })
                """.trimIndent()
            script { unsafe { +POSTHOG_SNIPPET } }
            script { unsafe { +init } }
        }
    }
    body {
        h1 { +"Order everything. Eat nothing." }
        p { +"One portion of nothing, 12,50 €." }
        form(action = "/checkout", method = FormMethod.post) {
            button { +(if (calm) "Order nothing, calmly" else "Order now") }
        }
    }
}

// PostHog's app for a cloud's ingestion host: eu.i.posthog.com → eu.posthog.com.
private fun uiHost(host: String?): String = host.orEmpty().replace(".i.posthog.com", ".posthog.com")

// posthog-js' loading snippet: a stub that queues calls until `/ingest/static/array.js` has loaded.
private val POSTHOG_SNIPPET =
    """
    !function(t,e){var o,n,p,r;e.__SV||(window.posthog=e,e._i=[],e.init=function(i,s,a){function g(t,e){var o=e.split(".");2==o.length&&(t=t[o[0]],e=o[1]),t[e]=function(){t.push([e].concat(Array.prototype.slice.call(arguments,0)))}}(p=t.createElement("script")).type="text/javascript",p.crossOrigin="anonymous",p.async=!0,p.src=s.api_host.replace(".i.posthog.com","-assets.i.posthog.com")+"/static/array.js",(r=t.getElementsByTagName("script")[0]).parentNode.insertBefore(p,r);var u=e;for(void 0!==a?u=e[a]=[]:a="posthog",u.people=u.people||[],u.toString=function(t){var e="posthog";return"posthog"!==a&&(e+="."+a),t||(e+=" (stub)"),e},u.people.toString=function(){return u.toString(1)+".people (stub)"},o="init capture register register_once register_for_session unregister unregister_for_session getFeatureFlag getFeatureFlagPayload isFeatureEnabled reloadFeatureFlags onFeatureFlags onSessionId identify setPersonProperties group resetGroups setPersonPropertiesForFlags resetPersonPropertiesForFlags setGroupPropertiesForFlags resetGroupPropertiesForFlags reset get_distinct_id getGroups get_session_id get_session_replay_url alias set_config startSessionRecording stopSessionRecording sessionRecordingStarted captureException loadToolbar get_property getSessionProperty createPersonProfile opt_in_capturing opt_out_capturing has_opted_in_capturing has_opted_out_capturing clear_opt_in_out_capturing debug".split(" "),n=0;n<o.length;n++)g(u,o[n]);e._i.push([i,s,a])},e.__SV=1)}(document,window.posthog||[]);
    """.trimIndent()
