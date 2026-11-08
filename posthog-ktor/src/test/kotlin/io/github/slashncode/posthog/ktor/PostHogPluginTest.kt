package io.github.slashncode.posthog.ktor

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.seconds

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
            FakePostHog().use { fake ->
                testApplication {
                    environment {
                        config = MapApplicationConfig("posthog.projectToken" to "phc_test", "posthog.host" to fake.url)
                    }
                    application {
                        install(PostHog)

                        posthog.isEnabled shouldBe true
                        posthog.capture("order_placed", mapOf("items" to 2, "coupon" to null), distinctId = "user-7")
                    }
                    startApplication()
                }

                eventually(10.seconds) { fake.events().size shouldBe 1 }
                val event = fake.events().single()
                event.string("event") shouldBe "order_placed"
                event.string("distinct_id") shouldBe "user-7"
                event.property("items") shouldBe "2"
                event.property("coupon") shouldBe null
            }
        }

        test("the install block wins over the application config") {
            FakePostHog().use { fake ->
                testApplication {
                    environment { config = MapApplicationConfig("posthog.projectToken" to "phc_from_config") }
                    application {
                        install(PostHog) {
                            projectToken = null
                            host = fake.url
                        }

                        posthog.isEnabled shouldBe false
                    }
                    startApplication()
                }
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
