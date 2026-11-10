package io.github.slashncode.posthog.ktor

import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class ExtrasTest :
    FunSpec({
        test("all flags with their values and payloads, without an exposure") {
            PostHogFake().use { fake ->
                fake.flag("new-checkout", true)
                fake.flag("checkout-button", "buy-now", """{"label":"Buy now"}""")
                fake.flag("product-reviews", false)
                postHogTest(fake, {
                    install(PostHog)
                    val posthog = posthog
                    routing {
                        get("/") {
                            val all = call.posthog.getAllFeatureFlags()
                            all.keys shouldBe setOf("new-checkout", "checkout-button", "product-reviews")
                            all["checkout-button"]!!.variant shouldBe "buy-now"
                            all["checkout-button"]!!.payload shouldBe """{"label":"Buy now"}"""
                            all["product-reviews"]!!.enabled shouldBe false
                            posthog.getAllFeatureFlags(distinctId = "user-2")["new-checkout"]!!.enabled shouldBe true
                            posthog.getAllFeatureFlags(distinctId = null).size shouldBe 3 // the call's person
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                    client.get("/nobody")
                }

                fake.assertNotCaptured("\$feature_flag_called")
            }

            testApplication {
                application {
                    install(PostHog)
                    routing { get("/") { call.respondText("${call.posthog.getAllFeatureFlags()}") } }
                }
                client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
            }
        }

        test("stopping does not wait longer than shutdownTimeoutMs for an unreachable PostHog") {
            // Accepts connections and never answers.
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { silent ->
                val held = CopyOnWriteArrayList<Socket>()
                thread(isDaemon = true) { runCatching { while (true) held += silent.accept() } }
                val started = System.nanoTime()
                testApplication {
                    application {
                        install(PostHog) {
                            projectToken = "phc_test"
                            host = "http://127.0.0.1:${silent.localPort}"
                            shutdownTimeoutMs = 300
                        }
                        repeat(5) { posthog.capture("queued", distinctId = "user-1") }
                    }
                    startApplication()
                }
                ((System.nanoTime() - started) / 1_000_000) shouldBeLessThan 5_000
                held.forEach { it.close() }
            }
        }

        test("surveys answered on the server are recorded as posthog-js records them") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog)
                    val posthog = posthog
                    routing {
                        get("/survey") {
                            call.posthog.captureSurveyShown("survey-1")
                            call.posthog.captureSurveySent(
                                "survey-1",
                                responses = mapOf("q-rating" to 5, "q-why" to "Fast", "q-tags" to listOf("price", "speed")),
                                questions =
                                    listOf(
                                        PostHogSurveyQuestion("q-rating", "How likely…?"),
                                        PostHogSurveyQuestion("q-why", "Why?"),
                                    ),
                                iteration = 2,
                                submissionId = "sub-1",
                            )
                            posthog.captureSurveyDismissed("survey-2", distinctId = "user-9")
                            posthog.captureSurveyShown("survey-2", distinctId = "user-9")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/survey") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertCaptured("survey shown") { property("\$survey_id", "survey-1") }.distinctId shouldBe "anon-1"
                val sent =
                    fake.assertCaptured("survey sent") {
                        property("\$survey_response_q-rating", "5")
                        property("\$survey_response_q-why", "Fast")
                        property("\$survey_iteration", 2)
                        property("\$survey_submission_id", "sub-1")
                        property("\$survey_completed", true)
                    }
                (sent.properties["\$survey_response_q-tags"] as JsonArray).map { it.jsonPrimitive.content } shouldContainExactly
                    listOf("price", "speed")
                (sent.properties["\$survey_questions"] as JsonArray)
                    .first()
                    .jsonObject["response"]!!
                    .jsonPrimitive.content shouldBe "5"
                (sent.properties["\$set"] as JsonObject)["\$survey_responded/survey-1/2"]!!.jsonPrimitive.content shouldBe "true"
                val dismissed = fake.assertCaptured("survey dismissed") { property("\$survey_partially_completed", false) }
                dismissed.distinctId shouldBe "user-9"
                (dismissed.properties["\$set"] as JsonObject)["\$survey_dismissed/survey-2"]!!.jsonPrimitive.content shouldBe "true"
            }
        }

        test("early access features: listed by stage for an opt-in page, enrollment changes the call's flags") {
            PostHogFake().use { fake ->
                fake.earlyAccessFeature(
                    "dark-mode",
                    "Dark mode",
                    stage = "beta",
                    description = "Easier on the eyes",
                    payload = """{"theme":"dark"}""",
                )
                fake.earlyAccessFeature("voice-search", "Voice search", stage = "concept")
                fake.flag("dark-mode", false)
                postHogTest(fake, {
                    install(PostHog)
                    val posthog = posthog
                    routing {
                        get("/") {
                            val beta = posthog.getEarlyAccessFeatures(listOf("beta"))
                            beta.map { it.flagKey } shouldBe listOf("dark-mode")
                            beta.single().let {
                                it.name shouldBe "Dark mode"
                                it.description shouldBe "Easier on the eyes"
                                it.documentationUrl shouldBe null
                                it.payload shouldBe """{"theme":"dark"}"""
                            }
                            posthog.getEarlyAccessFeatures().map { it.flagKey } shouldBe listOf("dark-mode", "voice-search")
                            call.posthog.isFeatureEnabled("dark-mode")
                            call.posthog.updateEarlyAccessFeatureEnrollment("dark-mode", isEnrolled = true, stage = "beta")
                            call.posthog.isFeatureEnabled("dark-mode")
                            posthog.updateEarlyAccessFeatureEnrollment("voice-search", isEnrolled = false, distinctId = "user-9")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                val enrolled = fake.assertCaptured("\$feature_enrollment_update") { property("\$feature_flag", "dark-mode") }
                enrolled.property("\$feature_enrollment") shouldBe true
                enrolled.property("\$feature_enrollment_stage") shouldBe "beta"
                (enrolled.properties["\$set"] as JsonObject)["\$feature_enrollment/dark-mode"]!!.jsonPrimitive.content shouldBe "true"
                fake.assertCaptured("\$feature_enrollment_update") { property("\$feature_flag", "voice-search") }.distinctId shouldBe
                    "user-9"
                fake.flagRequests.size shouldBe 2
                val personProperties = fake.flagRequests.last()["person_properties"] as JsonObject
                personProperties["\$feature_enrollment/dark-mode"]!!.jsonPrimitive.content shouldBe "true"
            }

            testApplication {
                application {
                    install(PostHog)
                    routing { get("/") { call.respondText("${posthog.getEarlyAccessFeatures().size}") } }
                }
                client.get("/")
            }
        }

        test("the session replay link of a call, on the app host of the cloud or the configured uiHost") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog) { uiHost = "https://posthog.example.com/" }
                    routing {
                        get("/") { call.respondText(call.posthog.sessionReplayUrl ?: "none") }
                    }
                }) {
                    val url = client.get("/") { header(SESSION_ID_HEADER, "session-1") }.bodyAsText()
                    url shouldBe "https://posthog.example.com/project/${fake.projectToken}/replay/session-1"
                    client.get("/").bodyAsText() shouldBe "none"
                }
            }

            testApplication {
                application {
                    install(PostHog) { host = POSTHOG_EU_HOST }
                    posthog.uiHost shouldBe "https://eu.posthog.com"
                    posthog.sessionReplayUrl("s") shouldBe null // PostHog is off
                }
                startApplication()
            }
        }
    })
