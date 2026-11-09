package io.github.slashncode.posthog.ktor.test

import io.github.slashncode.posthog.ktor.DISTINCT_ID_HEADER
import io.github.slashncode.posthog.ktor.PostHog
import io.github.slashncode.posthog.ktor.posthog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.milliseconds

class PostHogFakeTest :
    FunSpec({
        test("an app under test sends to the fake; assertions wait for what it captured") {
            PostHogFake().use { posthog ->
                posthog.flag("new-checkout", true)
                posthog.flag("checkout-copy", "calm", """{"button":"Nichts bestellen"}""")
                testApplication {
                    environment { config = posthog.applicationConfig() }
                    application {
                        install(PostHog)
                        routing {
                            post("/checkout") {
                                val copy = call.posthog.getFeatureFlag("checkout-copy")
                                call.posthog.capture(
                                    "order_placed",
                                    mapOf("total" to 12.0, "items" to 2, "express" to false, "currency" to "EUR", "coupon" to null),
                                )
                                call.respondText("$copy")
                            }
                        }
                    }

                    client.post("/checkout") { header(DISTINCT_ID_HEADER, "anon-1") }.bodyAsText() shouldBe "calm"

                    // Still inside the application: sent one by one, no need to stop it first.
                    val order =
                        posthog.assertCaptured("order_placed") {
                            property("total", 12)
                            property("items", 2L)
                            property("express", false)
                            property("currency", "EUR")
                            property("coupon", null)
                        }
                    order.distinctId shouldBe "anon-1"
                    order.flags shouldBe mapOf("checkout-copy" to "calm")
                    order.property("\$request_path") shouldBe "/checkout"
                    posthog.assertCaptured("\$feature_flag_called") { property("\$feature_flag", "checkout-copy") }
                    posthog.flagRequests.single()["distinct_id"].toString() shouldBe "\"anon-1\""
                    posthog.assertNotCaptured("order_cancelled", within = 100.milliseconds)
                }
            }
        }

        test("failed assertions say what did arrive") {
            PostHogFake().use { posthog ->
                testApplication {
                    environment { config = posthog.applicationConfig() }
                    application {
                        install(PostHog)
                        routing {
                            get("/") {
                                call.posthog.capture("page_viewed", mapOf("page" to "home"), distinctId = "anon-1")
                                call.respondText("ok")
                            }
                        }
                    }
                    client.get("/")
                    posthog.assertCaptured("page_viewed")

                    shouldThrow<AssertionError> {
                        posthog.assertCaptured("order_placed", timeout = 200.milliseconds)
                    }.message shouldContain "received: page_viewed"
                    val wrongValue =
                        shouldThrow<AssertionError> {
                            posthog.assertCaptured("page_viewed", timeout = 200.milliseconds) { property("page", "menu") }
                        }
                    wrongValue.cause!!.message shouldContain "to be <menu> but was <home>"
                    shouldThrow<AssertionError> { posthog.assertNotCaptured("page_viewed", within = 1.milliseconds) }

                    posthog.reset()
                    posthog.events shouldBe emptyList()
                }
            }
        }

        test("its configuration goes on top of the app's own") {
            PostHogFake().use { posthog ->
                val base = MapApplicationConfig("posthog.host" to "https://eu.i.posthog.com", "shop.name" to "NothingEats")
                val config = posthog.applicationConfig(base)
                config.property("posthog.host").getString() shouldBe posthog.host
                config.property("posthog.projectToken").getString() shouldBe posthog.projectToken
                config.property("shop.name").getString() shouldBe "NothingEats"
            }
            shouldThrow<IllegalArgumentException> { PostHogFake().use { it.flag("odds", 3) } }
        }
    })
