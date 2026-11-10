package io.github.slashncode.posthog.ktor

import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

private fun JsonObject.nested(
    key: String,
    inner: String,
): String? = (this[key] as? JsonObject)?.get(inner)?.jsonPrimitive?.content

class FlagApiTest :
    FunSpec({
        test("a flag's result: on or off, its variant and its payload in one read") {
            PostHogFake().use { fake ->
                fake.flag("checkout-button", "buy-now", """{"label":"Buy now"}""")
                fake.flag("product-reviews", false)
                postHogTest(fake, {
                    install(PostHog)
                    routing {
                        get("/") {
                            val button = call.posthog.getFeatureFlagResult("checkout-button")!!
                            button.key shouldBe "checkout-button"
                            button.enabled shouldBe true
                            button.variant shouldBe "buy-now"
                            button.value shouldBe "buy-now"
                            button.payload shouldBe """{"label":"Buy now"}"""
                            button.getPayloadAs<Map<String, String>>() shouldBe mapOf("label" to "Buy now")
                            button.getPayloadAs<Int>() shouldBe null

                            val reviews = call.posthog.getFeatureFlagResult("product-reviews")!!
                            reviews.enabled shouldBe false
                            reviews.variant shouldBe null
                            reviews.value shouldBe false

                            call.posthog.getFeatureFlagResult("unknown") shouldBe null
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertCaptured("\$feature_flag_called") { property("\$feature_flag", "checkout-button") }
            }
        }

        test("outside a call: flags evaluated for the context's person and groups, carried by the events captured with them") {
            PostHogFake().use { fake ->
                fake.flag("new-checkout", true)
                postHogTest(fake, {
                    install(PostHog)
                    val posthog = posthog
                    routing {
                        get("/job") {
                            posthog.withContext(distinctId = "user-9", groups = mapOf("company" to "acme"), fresh = true) {
                                val flags = posthog.evaluateFlags { personProperty("plan", "pro") }
                                flags.isEnabled("new-checkout") shouldBe true
                                posthog.capture("job_done", flags = flags)
                                posthog.capture("other_person", distinctId = "user-10", flags = flags)
                            }
                            posthog.evaluateFlags().keys shouldBe emptyList() // fresh context gone: the call's person, no id
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/job")
                }

                val request = fake.flagRequests.single()
                request["distinct_id"]!!.jsonPrimitive.content shouldBe "user-9"
                request.nested("groups", "company") shouldBe "acme"
                request.nested("person_properties", "plan") shouldBe "pro"
                fake.assertCaptured("job_done").flags shouldBe mapOf("new-checkout" to true)
                fake.assertCaptured("other_person").flags shouldBe emptyMap()
            }
        }

        test("person and group properties for flags, and identify's properties, evaluate the call's flags again") {
            PostHogFake().use { fake ->
                fake.flag("new-checkout", true)
                postHogTest(fake, {
                    install(PostHog)
                    routing {
                        get("/") {
                            call.posthog.isFeatureEnabled("new-checkout")
                            call.posthog.isFeatureEnabled("new-checkout")
                            call.posthog.setPersonPropertiesForFlags(mapOf("plan" to "pro"))
                            call.posthog.setGroupPropertiesForFlags(mapOf("company" to mapOf("size" to 50)))
                            call.posthog.isFeatureEnabled("new-checkout")
                            call.posthog.resetGroupPropertiesForFlags("company")
                            call.posthog.identify("user-1", mapOf("country" to "DE"))
                            call.posthog.isFeatureEnabled("new-checkout")
                            call.posthog.resetPersonPropertiesForFlags()
                            call.posthog.isFeatureEnabled("new-checkout")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                val requests = fake.flagRequests
                requests.size shouldBe 4
                requests[1].nested("person_properties", "plan") shouldBe "pro"
                ((requests[1]["group_properties"] as JsonObject)["company"] as JsonObject)["size"]!!.jsonPrimitive.content shouldBe "50"
                requests[2]["distinct_id"]!!.jsonPrimitive.content shouldBe "user-1"
                requests[2].nested("person_properties", "country") shouldBe "DE"
                requests[2].nested("person_properties", "plan") shouldBe "pro"
                (requests[2]["group_properties"] as? JsonObject)?.get("company") shouldBe null
                requests[3].nested("person_properties", "plan") shouldBe null
            }
        }

        test("reloadFeatureFlags loads the definitions for local evaluation again") {
            fun definition(rollout: Int) =
                """
                {"flags":[{"id":1,"key":"new-checkout","active":true,"deleted":false,"ensure_experience_continuity":false,
                  "filters":{"groups":[{"properties":[],"rollout_percentage":$rollout}]}}],
                 "group_type_mapping":{},"cohorts":{}}
                """.trimIndent()
            PostHogFake().use { fake ->
                fake.flagDefinitions = definition(100)
                postHogTest(fake, {
                    install(PostHog) {
                        secretKey = "phs_test"
                        pollIntervalSeconds = 3600
                    }
                    val posthog = posthog
                    routing {
                        get("/") { call.respondText("${call.posthog.isFeatureEnabled("new-checkout")}") }
                        get("/reload") {
                            posthog.reloadFeatureFlags()
                            call.respondText("ok")
                        }
                    }
                }) {
                    eventually(10.seconds) { client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }.bodyAsText() shouldBe "true" }
                    fake.flagDefinitions = definition(0)
                    client.get("/reload")
                    eventually(10.seconds) { client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }.bodyAsText() shouldBe "false" }
                }
                fake.flagRequests shouldBe emptyList()
            }
        }
    })
