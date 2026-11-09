package io.github.slashncode.posthog.ktor

import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.principal
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

private fun PostHogFake.event(name: String) = sent().single { it.string("event") == name }

private val basicAuth = "Basic " + Base64.getEncoder().encodeToString("ada:secret".toByteArray())

class PostHogCallTest :
    FunSpec({
        test("events of a call carry the browser's ids and the request's properties") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(XForwardedHeaders)
                    install(PostHog)
                    routing {
                        get("/restaurants/{slug}/menu") {
                            call.posthog.capture("menu_viewed", mapOf("dishes" to 6))
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/restaurants/phantom-pizza/menu?lang=de") {
                        header(DISTINCT_ID_HEADER, "anon-1")
                        header(SESSION_ID_HEADER, "session-9")
                        header(HttpHeaders.UserAgent, "Firefox")
                        header(HttpHeaders.XForwardedFor, "203.0.113.7")
                    }
                }

                eventually(10.seconds) { fake.sent().size shouldBe 1 }
                val event = fake.event("menu_viewed")
                event.string("distinct_id") shouldBe "anon-1"
                event.property("\$session_id") shouldBe "session-9"
                event.property("\$current_url") shouldBe "http://localhost/restaurants/phantom-pizza/menu?lang=de"
                event.property("\$request_method") shouldBe "GET"
                event.property("\$request_path") shouldBe "/restaurants/phantom-pizza/menu"
                event.property("\$route") shouldBe "/restaurants/{slug}/menu"
                event.property("\$user_agent") shouldBe "Firefox"
                event.property("\$ip") shouldBe "203.0.113.7"
                event.property("dishes") shouldBe "6"
            }
        }

        test("the authenticated user wins over the header; identify over both; an explicit id over everything") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(Authentication) {
                        basic("basic") { validate { UserIdPrincipal(it.name) } }
                    }
                    install(PostHog) { resolveDistinctId { it.principal<UserIdPrincipal>()?.name } }
                    routing {
                        authenticate("basic") {
                            post("/orders/{id}") {
                                call.posthog.capture("as_user")
                                call.posthog.capture("explicit", distinctId = "someone-else")
                                call.posthog.identify("ada-renamed", properties = mapOf("plan" to "nothing"))
                                call.posthog.capture("after_identify")
                                call.respondText("ok")
                            }
                        }
                        get("/public") {
                            call.posthog.capture("as_visitor")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.post("/orders/42") {
                        header(DISTINCT_ID_HEADER, "anon-1")
                        header(HttpHeaders.Authorization, basicAuth)
                    }
                    client.get("/public") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                eventually(10.seconds) { fake.sent().count { !it.string("event")!!.startsWith("$") } shouldBe 4 }
                fake.event("\$identify").string("distinct_id") shouldBe "ada-renamed"
                fake.event("as_user").string("distinct_id") shouldBe "ada"
                fake.event("as_user").property("\$route") shouldBe "/orders/{id}"
                fake.event("explicit").string("distinct_id") shouldBe "someone-else"
                fake.event("after_identify").string("distinct_id") shouldBe "ada-renamed"
                fake.event("as_visitor").string("distinct_id") shouldBe "anon-1"
            }
        }

        test("the context survives thread switches and reaches direct SDK calls") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog)
                    routing {
                        get("/slow") {
                            withContext(Dispatchers.IO) {
                                delay(20)
                                call.application.posthog.capture("on_another_thread")
                                call.application.posthog.sdk!!
                                    .capture("straight_to_the_sdk")
                            }
                            PostHogContext.current()!!.distinctId shouldBe "anon-2"
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/slow") {
                        header(DISTINCT_ID_HEADER, "anon-2")
                        header(SESSION_ID_HEADER, "session-2")
                    }
                }

                eventually(10.seconds) { fake.sent().size shouldBe 2 }
                listOf("on_another_thread", "straight_to_the_sdk").forEach { name ->
                    fake.event(name).string("distinct_id") shouldBe "anon-2"
                    fake.event(name).property("\$session_id") shouldBe "session-2"
                    fake.event(name).property("\$route") shouldBe "/slow"
                }
            }
        }

        test("without any id an event goes out without a person, or not at all when that is turned off") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog)
                    routing {
                        get("/") {
                            call.posthog.capture("anonymous")
                            call.respondText("ok")
                        }
                    }
                }) { client.get("/") }

                eventually(10.seconds) { fake.sent().size shouldBe 1 }
                val event = fake.event("anonymous")
                event.string("distinct_id") shouldNotBe null
                event.property("\$process_person_profile") shouldBe "false"
            }

            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog) { captureWithoutDistinctId = false }
                    routing {
                        get("/") {
                            call.posthog.capture("dropped")
                            call.posthog.capture("kept", distinctId = "user-1")
                            call.respondText("ok")
                        }
                    }
                }) { client.get("/") }

                eventually(10.seconds) { fake.sent().map { it.string("event") } shouldBe listOf("kept") }
            }
        }

        test("tracing headers can be ignored; properties can be added, changed and left out; calls can be skipped") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog) {
                        useTracingHeaders = false
                        captureIp = false
                        extraProperties { mapOf("service" to "ordering", "none" to null) }
                        propertiesMap { it - "\$current_url" }
                        requestFilter { it.request.local.uri != "/health" }
                    }
                    routing {
                        get("/") {
                            call.posthog.capture("tidy")
                            call.respondText("ok")
                        }
                        get("/health") {
                            call.posthog.capture("health", distinctId = "probe")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-3") }
                    client.get("/health")
                }

                eventually(10.seconds) { fake.sent().size shouldBe 2 }
                val tidy = fake.event("tidy").getValue("properties").jsonObject
                fake.event("tidy").property("\$process_person_profile") shouldBe "false"
                tidy.string("service") shouldBe "ordering"
                tidy shouldNotContainKey "none"
                tidy shouldNotContainKey "\$current_url"
                tidy shouldNotContainKey "\$ip"
                tidy.string("\$request_path") shouldBe "/"
                fake.event("health").getValue("properties").jsonObject shouldNotContainKey "\$request_path"
            }
        }

        test("tracing headers are trimmed and capped at 1,000 characters") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog)
                    routing {
                        get("/") {
                            call.posthog.capture("cleaned")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "  anon-" + "x".repeat(2000)) }
                }

                eventually(10.seconds) { fake.sent().size shouldBe 1 }
                fake.event("cleaned").string("distinct_id") shouldBe ("anon-" + "x".repeat(995))
            }
        }
    })
