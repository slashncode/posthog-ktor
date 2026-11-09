package io.github.slashncode.posthog.ktor

import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

private val shopFlags =
    mapOf(
        "new-checkout" to Flag(true),
        "checkout-copy" to Flag("calm", """{"button":"Nichts bestellen"}"""),
        "coach-placement" to Flag(false),
        "deal-countdown" to Flag("until"),
    )

private fun PostHogFake.exposures() =
    sent().filter { it.string("event") == "\$feature_flag_called" }.map {
        it.property("\$feature_flag") to
            it.property("\$feature_flag_response")
    }

class FeatureFlagsTest :
    FunSpec({
        test("a call evaluates its flags once; its events carry the flags it read, exposures recorded once") {
            PostHogFake().use { fake ->
                fake.remoteFlags = shopFlags
                postHogTest(fake, {
                    install(PostHog) { sdk { featureFlagCacheMaxAgeMs(0) } } // every evaluation asks the fake
                    routing {
                        get("/checkout") {
                            val posthog = call.posthog
                            posthog.isFeatureEnabled("new-checkout") shouldBe true
                            posthog.isFeatureEnabled("new-checkout") shouldBe true
                            posthog.getFeatureFlag("checkout-copy") shouldBe "calm"
                            posthog.flags().getFlagPayloadAs<Map<String, String>>("checkout-copy") shouldBe
                                mapOf("button" to "Nichts bestellen")
                            posthog.flags().getFlagPayloadAs<Int>("checkout-copy") shouldBe null
                            posthog.flags().keys shouldContainExactlyInAnyOrder shopFlags.keys
                            posthog.capture("checkout_viewed")
                            error("kaputt")
                        }
                    }
                }) {
                    client.get("/checkout") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.flagRequests.size shouldBe 1
                fake.flagRequests.single().string("distinct_id") shouldBe "anon-1"
                eventually(10.seconds) { fake.exposures().size shouldBe 2 }
                fake.exposures() shouldContainExactlyInAnyOrder listOf("new-checkout" to "true", "checkout-copy" to "calm")
                for (name in listOf("checkout_viewed", "\$exception")) {
                    val event = fake.sent().single { it.string("event") == name }
                    event.property("\$feature/new-checkout") shouldBe "true"
                    event.property("\$feature/checkout-copy") shouldBe "calm"
                    event.property("\$feature/deal-countdown") shouldBe null
                    event.arrayProperty("\$active_feature_flags")!! shouldContainExactlyInAnyOrder listOf("new-checkout", "checkout-copy")
                }
            }
        }

        test("without a distinct id, or with PostHog off, every flag is off and PostHog is not asked") {
            PostHogFake().use { fake ->
                fake.remoteFlags = shopFlags
                postHogTest(fake, {
                    install(PostHog)
                    routing { get("/") { call.respondText("${call.posthog.isFeatureEnabled("new-checkout")}") } }
                }) {
                    client.get("/").bodyAsText() shouldBe "false"
                }
                fake.flagRequests shouldBe emptyList()
            }

            testApplication {
                install(PostHog)
                routing { get("/") { call.respondText("${call.posthog.flags().isEnabled("new-checkout")}") } }
                client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }.bodyAsText() shouldBe "false"
            }
        }

        test("flags follow the distinct id, are evaluated with the flag context, and stay off events if asked") {
            PostHogFake().use { fake ->
                fake.remoteFlags = shopFlags
                postHogTest(fake, {
                    install(PostHog) {
                        attachAccessedFlags = false
                        flagContext { call ->
                            call.request.queryParameters["plan"]?.let { personProperty("plan", it) }
                            group("company", "nothing-gmbh")
                        }
                    }
                    routing {
                        get("/login") {
                            call.posthog.isFeatureEnabled("new-checkout")
                            call.posthog.identify("user-1")
                            call.posthog.isFeatureEnabled("new-checkout")
                            call.posthog.capture("logged_in")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/login?plan=pro") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.flagRequests.map { it.string("distinct_id") } shouldBe listOf("anon-1", "user-1")
                val request = fake.flagRequests.last()
                request["person_properties"]!!.jsonObject["plan"]!!.jsonPrimitive.content shouldBe "pro"
                request["groups"]!!.jsonObject["company"]!!.jsonPrimitive.content shouldBe "nothing-gmbh"
                eventually(10.seconds) { fake.sent().any { it.string("event") == "logged_in" } shouldBe true }
                fake.sent().single { it.string("event") == "logged_in" }.property("\$feature/new-checkout") shouldBe null
            }
        }

        test("with a secret key, flags are evaluated locally") {
            PostHogFake().use { fake ->
                fake.flagDefinitions =
                    """
                    {"flags":[{"id":1,"key":"new-checkout","active":true,"deleted":false,"ensure_experience_continuity":false,
                      "filters":{"groups":[{"properties":[],"rollout_percentage":100}]}}],
                     "group_type_mapping":{},"cohorts":{}}
                    """.trimIndent()
                postHogTest(fake, {
                    install(PostHog) { secretKey = "phs_test" }
                    routing {
                        get("/") {
                            val flags = call.posthog.flags()
                            // Only conclusive once the definitions have loaded; no remote fallback meanwhile.
                            call.respondText("${flags.isEnabled("new-checkout")}")
                        }
                    }
                }) {
                    eventually(10.seconds) {
                        client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }.bodyAsText() shouldBe "true"
                    }
                }

                delay(300)
                eventually(10.seconds) {
                    fake.sent().any {
                        it.string("event") == "\$feature_flag_called" && it.property("locally_evaluated") == "true"
                    } shouldBe true
                }
            }
        }
    })
