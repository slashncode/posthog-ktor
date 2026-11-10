package io.github.slashncode.posthog.ktor

import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.seconds
import com.posthog.server.PostHog as PostHogSdk
import com.posthog.server.PostHogConfig as PostHogSdkConfig

class PostHogPluginTest :
    FunSpec({
        test("without a project token PostHog stays off and capturing does nothing") {
            testApplication {
                application {
                    install(PostHog)

                    posthog.isEnabled shouldBe false
                    posthog.sdk shouldBe null
                    posthog.capture("anything", distinctId = "user-1")
                }
            }
        }

        test("the posthog section of the application config sets it up; events leave on shutdown at the latest") {
            PostHogFake().use { fake ->
                testApplication {
                    environment {
                        config = MapApplicationConfig("posthog.projectToken" to "phc_test", "posthog.host" to fake.host)
                    }
                    application {
                        install(PostHog)

                        posthog.isEnabled shouldBe true
                        posthog.capture("order_placed", mapOf("items" to 2, "coupon" to null), distinctId = "user-7")
                    }
                    startApplication()
                }

                eventually(10.seconds) { fake.sent().size shouldBe 1 }
                val event = fake.sent().single()
                event.string("event") shouldBe "order_placed"
                event.string("distinct_id") shouldBe "user-7"
                event.property("items") shouldBe "2"
                event.property("coupon") shouldBe null
            }
        }

        test("the install block wins over the application config") {
            PostHogFake().use { fake ->
                testApplication {
                    environment { config = MapApplicationConfig("posthog.projectToken" to "phc_from_config") }
                    application {
                        install(PostHog) {
                            projectToken = null
                            host = fake.host
                        }

                        posthog.isEnabled shouldBe false
                    }
                    startApplication()
                }
            }
        }

        test("a client made before the application: calls capture with it, the application leaves it open, close() ends it") {
            PostHogFake().use { fake ->
                val shared = PostHogClient(fake.applicationConfig().config("posthog")) { captureWithoutDistinctId = false }
                shared.isEnabled shouldBe true
                shared.capture("job_ran", distinctId = "worker-1")
                fake.assertCaptured("job_ran") { distinctId shouldBe "worker-1" }

                testApplication {
                    application {
                        install(PostHog) {
                            client = shared
                            projectToken = null // a client option: ignored, the shared client has its own
                        }
                        posthog shouldBe shared
                        routing {
                            get("/orders") {
                                call.posthog.capture("order_viewed")
                                call.respondText("ok")
                            }
                        }
                    }
                    client.get("/orders") { header(DISTINCT_ID_HEADER, "anon-1") }
                    client.get("/orders")
                }

                fake.assertCaptured("order_viewed") {
                    distinctId shouldBe "anon-1"
                    property("\$request_path", "/orders")
                }
                shared.isEnabled shouldBe true
                shared.capture("after_stop", distinctId = "worker-1")
                fake.assertCaptured("after_stop")

                shared.close()
                shared.close()
                shared.isEnabled shouldBe false
                shared.capture("after_close", distinctId = "worker-1")
                fake.assertNotCaptured("after_close")
                fake.captured("order_viewed").size shouldBe 1
            }
        }

        test("an official client made elsewhere: used as it is, flushed but not closed") {
            PostHogFake().use { fake ->
                val sdk =
                    PostHogSdk.with(
                        PostHogSdkConfig
                            .builder(fake.projectToken)
                            .host(fake.host)
                            .flushAt(1)
                            .build(),
                    )
                val client = PostHogClient { sdkClient = sdk }
                client.sdk shouldBe sdk
                client.capture("order_placed", distinctId = "user-1")
                fake.assertCaptured("order_placed")

                client.close()
                sdk.capture("user-1", "still_open")
                fake.assertCaptured("still_open")
                sdk.close()
            }
        }

        test("without the plugin there is no client, and the error says what is missing") {
            testApplication {
                application {
                    shouldThrow<IllegalStateException> { posthog }.message shouldBe
                        "PostHog is not installed: add install(PostHog) to the application module"
                }
                startApplication()
            }
        }
    })
