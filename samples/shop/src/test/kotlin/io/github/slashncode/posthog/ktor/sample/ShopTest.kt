package io.github.slashncode.posthog.ktor.sample

import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class ShopTest :
    FunSpec({
        test("a first visit gets a minted id, the server-rendered variant and posthog-js bootstrapped with it") {
            PostHogFake().use { posthog ->
                posthog.flag(CHECKOUT_COPY, "calm")
                testApplication {
                    environment { config = posthog.applicationConfig() }
                    application { module() }

                    val page = client.get("/").bodyAsText()
                    page shouldContain "Order nothing, calmly"
                    page shouldContain "api_host: '/ingest'"
                    page shouldContain """"featureFlags":{"checkout-copy":"calm"}"""
                    page shouldContain """"isIdentifiedID":false"""

                    val exposure = posthog.assertCaptured("\$feature_flag_called") { property("\$feature_flag", CHECKOUT_COPY) }
                    page shouldContain """"distinctID":"${exposure.distinctId}""""
                }
            }
        }

        test("orders are captured for the signed-in user; express checkout exists only behind its flag") {
            PostHogFake().use { posthog ->
                testApplication {
                    environment { config = posthog.applicationConfig() }
                    application { module() }
                    val client = createClient { followRedirects = false }

                    client.post("/checkout") { cookie("user", "ada") }.status shouldBe HttpStatusCode.Found
                    posthog
                        .assertCaptured("order_placed") {
                            property("total", 12.5)
                            property("express", null)
                        }.distinctId shouldBe "ada"

                    client.post("/checkout/express") { cookie("user", "ada") }.status shouldBe HttpStatusCode.NotFound
                    posthog.flag(EXPRESS_CHECKOUT, true)
                    client.post("/checkout/express") { cookie("user", "grace") }.status shouldBe HttpStatusCode.Found
                    posthog.assertCaptured("order_placed") { property("express", true) }.flags shouldBe mapOf(EXPRESS_CHECKOUT to true)
                }
            }
        }

        test("without a project token the shop works and loads no PostHog") {
            testApplication {
                application { module() }
                val page = client.get("/").bodyAsText()
                page shouldContain "Order now"
                page shouldNotContain "posthog.init"
            }
        }
    })
