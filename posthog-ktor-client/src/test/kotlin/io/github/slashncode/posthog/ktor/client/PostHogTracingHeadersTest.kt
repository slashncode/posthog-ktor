package io.github.slashncode.posthog.ktor.client

import com.posthog.server.PostHogRequestContext
import com.posthog.server.PostHogRequestContextData
import io.github.slashncode.posthog.ktor.DISTINCT_ID_HEADER
import io.github.slashncode.posthog.ktor.PostHog
import io.github.slashncode.posthog.ktor.PostHogContext
import io.github.slashncode.posthog.ktor.SESSION_ID_HEADER
import io.github.slashncode.posthog.ktor.posthog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Headers
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A client whose requests end in a mock engine that keeps their headers by URL. */
private class Downstream {
    val headers = ConcurrentHashMap<String, Headers>()
    val client =
        HttpClient(
            MockEngine { request ->
                headers[request.url.toString()] = request.headers
                respondOk()
            },
        ) {
            install(PostHogTracingHeaders) { hosts += listOf("orders.internal", "*.svc") }
        }

    fun ids(url: String) =
        headers.entries
            .single { it.key.equals(url, ignoreCase = true) }
            .value
            .let { it[DISTINCT_ID_HEADER] to it[SESSION_ID_HEADER] }
}

class PostHogTracingHeadersTest :
    FunSpec({
        test("a call's ids go on to the allowed hosts only, also from other threads; a request's own header stays") {
            val downstream = Downstream()
            testApplication {
                install(PostHog)
                routing {
                    get("/checkout") {
                        downstream.client.get("http://orders.internal/orders")
                        withContext(Dispatchers.IO) { downstream.client.get("http://payments.svc/quote") }
                        downstream.client.get("http://svc/bare")
                        downstream.client.get("https://example.com/elsewhere")
                        downstream.client.get("http://Orders.Internal/own") { header(DISTINCT_ID_HEADER, "explicit") }
                        call.respondText("ok")
                    }
                }
                client.get("/checkout") {
                    header(DISTINCT_ID_HEADER, "anon-1")
                    header(SESSION_ID_HEADER, "session-1")
                }
            }

            downstream.ids("http://orders.internal/orders") shouldBe ("anon-1" to "session-1")
            downstream.ids("http://payments.svc/quote") shouldBe ("anon-1" to "session-1")
            downstream.ids("http://svc/bare") shouldBe (null to null)
            downstream.ids("https://example.com/elsewhere") shouldBe (null to null)
            downstream.ids("http://orders.internal/own") shouldBe ("explicit" to "session-1")
        }

        test("outside a call: the context of the coroutine, else the SDK's request context, else nothing") {
            val downstream = Downstream()
            withContext(PostHogContext(distinctId = "job-1")) { downstream.client.get("http://orders.internal/job") }
            val scope = PostHogRequestContext.beginScope(PostHogRequestContextData("sdk-1", "session-9", emptyMap()), true)
            try {
                runBlocking { downstream.client.get("http://orders.internal/sdk") }
            } finally {
                scope.close()
            }
            downstream.client.get("http://orders.internal/nobody")

            downstream.ids("http://orders.internal/job") shouldBe ("job-1" to null)
            downstream.ids("http://orders.internal/sdk") shouldBe ("sdk-1" to "session-9")
            downstream.ids("http://orders.internal/nobody") shouldBe (null to null)
        }

        test("a context bound to the application's client works the same") {
            val downstream = Downstream()
            testApplication {
                install(PostHog)
                routing {
                    get("/") {
                        call.application.posthog.withContext(distinctId = "user-1", sessionId = "session-1") {
                            downstream.client.get("http://orders.internal/bound")
                        }
                        call.respondText("ok")
                    }
                }
                client.get("/")
            }
            downstream.ids("http://orders.internal/bound") shouldBe ("user-1" to "session-1")
        }
    })
