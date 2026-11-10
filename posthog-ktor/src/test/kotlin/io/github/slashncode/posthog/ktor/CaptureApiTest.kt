package io.github.slashncode.posthog.ktor

import com.posthog.PersonProfiles
import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

class CaptureApiTest :
    FunSpec({
        test("beforeSend hooks see every event with the super properties, change or drop it, in order; a failing hook drops it") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog) {
                        beforeSend { event -> event.apply { properties?.remove("email") } }
                        beforeSend { event -> if (event.event == "secret") null else event }
                        beforeSend { event -> if (event.event == "boom") error("hook failed") else event }
                        beforeSend { event -> event.apply { properties?.set("seen_version", properties?.get("app_version") ?: "none") } }
                    }
                    posthog.register(mapOf("app_version" to "1.2"))
                    routing {
                        get("/") {
                            call.posthog.capture("signed_up", mapOf("email" to "ada@example.com", "plan" to "pro"))
                            call.posthog.capture("secret")
                            call.posthog.capture("boom")
                            error("broken")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertCaptured("signed_up") {
                    property("email", null)
                    property("plan", "pro")
                    property("seen_version", "1.2")
                }
                fake.assertCaptured("\$exception") { property("seen_version", "1.2") }
                fake.assertNotCaptured("secret")
                fake.assertNotCaptured("boom")
            }
        }

        test("super properties: register, registerOnce, unregister; the context's and the event's own win") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog) { extraProperties { mapOf("tier" to "from-call") } }
                    val posthog = posthog
                    posthog.register(mapOf("app_version" to "1.2", "tier" to "super", "region" to "eu", "gone" to "soon"))
                    posthog.registerOnce(mapOf("app_version" to "9.9", "build" to 7))
                    posthog.unregister("gone")
                    posthog.superProperties shouldBe mapOf("app_version" to "1.2", "tier" to "super", "region" to "eu", "build" to 7)
                    routing {
                        get("/") {
                            call.posthog.capture("viewed", mapOf("region" to "us"))
                            posthog.sdk!!.capture("anon-1", "direct")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertCaptured("viewed") {
                    property("app_version", "1.2")
                    property("build", 7)
                    property("tier", "from-call")
                    property("region", "us")
                    property("gone", null)
                }
                fake.assertCaptured("direct") { property("app_version", "1.2") }
            }
        }

        test("opted out, nothing is sent, the SDK's own events included, and every flag is off; opted in, it captures again") {
            PostHogFake().use { fake ->
                fake.flag("new-checkout", true)
                postHogTest(fake, {
                    install(PostHog) { optOut = true }
                    val posthog = posthog
                    routing {
                        get("/") {
                            posthog.isOptedOut shouldBe true
                            posthog.isEnabled shouldBe false
                            call.posthog.isFeatureEnabled("new-checkout") shouldBe false
                            call.posthog.bootstrap(listOf("new-checkout"), mintDistinctId = true).distinctId shouldBe null
                            call.posthog.capture("while_out")
                            posthog.sdk!!.capture("anon-1", "direct_while_out")
                            posthog.optIn()
                            call.posthog.capture("back_in")
                            posthog.optOut()
                            call.posthog.capture("out_again")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertCaptured("back_in")
                fake.assertNotCaptured("while_out")
                fake.assertNotCaptured("direct_while_out")
                fake.assertNotCaptured("out_again")
                fake.flagRequests shouldBe emptyList()
            }
        }

        test("a call's groups go on every later event of the call and into its flag evaluation; capture adds groups") {
            PostHogFake().use { fake ->
                fake.flag("new-checkout", true)
                postHogTest(fake, {
                    install(PostHog)
                    val posthog = posthog
                    routing {
                        get("/") {
                            call.posthog.capture("before_group")
                            call.posthog.group("company", "acme", mapOf("name" to "Acme", "plan" to null))
                            call.posthog.isFeatureEnabled("new-checkout")
                            call.posthog.capture("report_exported", groups = mapOf("team" to "billing"))
                            posthog.capture("from_application")
                            posthog.withContext(distinctId = "user-2", groups = mapOf("team" to "ops")) { posthog.capture("job_done") }
                            posthog.withContext(distinctId = "user-3", fresh = true) { posthog.capture("fresh_job") }
                            posthog.groupIdentify("company", "globex")
                            error("broken")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertCaptured("before_group").groups shouldBe emptyMap()
                val identify = fake.assertCaptured("\$groupidentify") { property("\$group_key", "acme") }
                identify.distinctId shouldBe "anon-1"
                identify.property("\$group_type") shouldBe "company"
                (identify.property("\$group_set") as JsonObject)["name"]!!.jsonPrimitive.content shouldBe "Acme"
                fake.assertCaptured("report_exported").groups shouldBe mapOf("company" to "acme", "team" to "billing")
                fake.assertCaptured("from_application").groups shouldBe mapOf("company" to "acme")
                fake.assertCaptured("job_done").let {
                    it.distinctId shouldBe "user-2"
                    it.groups shouldBe mapOf("company" to "acme", "team" to "ops")
                    it.property("\$current_url").toString() shouldEndWith "/"
                }
                fake.assertCaptured("fresh_job").let {
                    it.groups shouldBe emptyMap()
                    it.property("\$current_url") shouldBe null
                }
                fake.assertCaptured("\$exception").groups shouldBe mapOf("company" to "acme")
                fake.assertCaptured("\$groupidentify") { property("\$group_key", "globex") }.distinctId shouldBe "anon-1"
                (fake.flagRequests.single()["groups"] as JsonObject)["company"]!!.jsonPrimitive.content shouldBe "acme"
            }
        }

        test("person properties, pageviews, alias, timestamps, and posthog-js' enriched flag analytics") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog)
                    val posthog = posthog
                    routing {
                        get("/page") {
                            call.posthog.setPersonProperties(mapOf("plan" to "pro"), mapOf("first_plan" to "free"))
                            call.posthog.unsetPersonProperties(listOf("trial_ends"))
                            call.posthog.capturePageview(mapOf("title" to "Products"))
                            call.posthog.alias("legacy-42")
                            call.posthog.capture("imported", timestamp = Instant.parse("2026-01-02T03:04:05Z"))
                            call.posthog.captureFeatureView("new-checkout")
                            call.posthog.captureFeatureInteraction("new-checkout")
                            posthog.capturePageview("https://example.com/feed.xml", distinctId = "reader-1")
                            posthog.setPersonProperties(mapOf("newsletter" to true), distinctId = "reader-1")
                            posthog.unsetPersonProperties(listOf("legacy_plan"), distinctId = "reader-1")
                            posthog.captureFeatureView("product-reviews", distinctId = "reader-1")
                            posthog.captureFeatureInteraction("product-reviews", distinctId = "reader-1")
                            posthog.debug(true)
                            posthog.debug(false)
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/page") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertCaptured("\$set") {
                    distinctId shouldBe "anon-1"
                    property("\$current_url").toString() shouldEndWith "/page"
                    (properties["\$set"] as JsonObject)["plan"]!!.jsonPrimitive.content shouldBe "pro"
                    (properties["\$set_once"] as JsonObject)["first_plan"]!!.jsonPrimitive.content shouldBe "free"
                }
                fake.assertCaptured("\$set") {
                    (properties["\$unset"] as? JsonArray)?.map { it.jsonPrimitive.content } shouldBe listOf("trial_ends")
                }
                fake.assertCaptured("\$set") { distinctId shouldBe "reader-1" }
                fake.assertCaptured("\$set") {
                    distinctId shouldBe "reader-1"
                    (properties["\$unset"] as? JsonArray)?.map { it.jsonPrimitive.content } shouldBe listOf("legacy_plan")
                }
                fake.assertCaptured("\$feature_view") { property("feature_flag", "product-reviews") }.distinctId shouldBe "reader-1"
                fake.assertCaptured("\$feature_interaction") { property("feature_flag", "product-reviews") }.distinctId shouldBe "reader-1"

                fake.assertCaptured("\$pageview") { property("title", "Products") }.property("\$current_url").toString() shouldEndWith
                    "/page"
                fake.assertCaptured("\$pageview") {
                    distinctId shouldBe "reader-1"
                    property("\$current_url", "https://example.com/feed.xml")
                }
                fake.assertCaptured("\$create_alias") { property("alias", "legacy-42") }.distinctId shouldBe "anon-1"
                fake
                    .assertCaptured("imported")
                    .json["timestamp"]!!
                    .jsonPrimitive.content shouldStartWith "2026-01-02T03:04:05"
                fake.assertCaptured("\$feature_view") { property("feature_flag", "new-checkout") }
                val interaction = fake.assertCaptured("\$feature_interaction") { property("feature_flag", "new-checkout") }
                (interaction.properties["\$set"] as JsonObject)["\$feature_interaction/new-checkout"]!!.jsonPrimitive.content shouldBe
                    "true"
            }
        }

        test("identified only: a browser's anonymous id gets no person profile until the call names or changes the person") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog) {
                        personProfiles = PersonProfiles.IDENTIFIED_ONLY
                        resolveDistinctId { it.request.headers["X-User"] }
                    }
                    routing {
                        get("/anonymous") {
                            call.posthog.capture("anonymous_view")
                            call.posthog.capture("named_view", distinctId = "user-9")
                            call.posthog.setPersonProperties(mapOf("plan" to "pro"))
                            call.posthog.capture("after_set")
                            call.respondText("ok")
                        }
                        get("/user") {
                            call.posthog.capture("user_view")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/anonymous") { header(DISTINCT_ID_HEADER, "anon-1") }
                    client.get("/user") {
                        header(DISTINCT_ID_HEADER, "anon-2")
                        header("X-User", "user-1")
                    }
                }

                fake.assertCaptured("anonymous_view") { property("\$process_person_profile", false) }
                fake.assertCaptured("named_view").property("\$process_person_profile") shouldNotBe false
                fake.assertCaptured("\$set").property("\$process_person_profile") shouldNotBe false
                fake.assertCaptured("after_set").property("\$process_person_profile") shouldNotBe false
                fake.assertCaptured("user_view").property("\$process_person_profile") shouldNotBe false
            }
        }

        test("never: no event creates a person profile") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog) { personProfiles = PersonProfiles.NEVER }
                    routing {
                        get("/") {
                            call.posthog.identify("user-1", mapOf("plan" to "pro"))
                            call.posthog.capture("viewed")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertCaptured("viewed") { property("\$process_person_profile", false) }
                fake.assertCaptured("\$identify") { property("\$process_person_profile", false) }
            }
        }

        test("GeoIP: only from the visitor's address; events without one, and flags, skip it; disableGeoip skips it everywhere") {
            PostHogFake().use { fake ->
                fake.flag("new-checkout", true)
                postHogTest(fake, {
                    install(PostHog)
                    val posthog = posthog
                    routing {
                        get("/") {
                            call.posthog.capture("in_call")
                            call.posthog.isFeatureEnabled("new-checkout")
                            posthog.withContext(distinctId = "user-1", fresh = true) { posthog.capture("outside_call") }
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertCaptured("in_call").let {
                    it.property("\$ip") shouldNotBe null
                    it.property("\$geoip_disable") shouldBe null
                }
                fake.assertCaptured("outside_call") { property("\$geoip_disable", true) }
                fake.flagRequests
                    .single()["geoip_disable"]!!
                    .jsonPrimitive.content shouldBe "true"
            }

            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog) { disableGeoip = true }
                    routing {
                        get("/") {
                            call.posthog.capture("in_call")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }
                fake.assertCaptured("in_call") { property("\$geoip_disable", true) }
            }
        }

        test("calls from blocked user agents are left alone") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog) { customBlockedUserAgents += "uptimerobot" }
                    routing {
                        get("/") {
                            call.posthog.distinctId shouldBe null
                            error("broken")
                        }
                    }
                }) {
                    client.get("/") {
                        header(DISTINCT_ID_HEADER, "anon-1")
                        header(HttpHeaders.UserAgent, "Mozilla/5.0+(compatible; UptimeRobot/2.0)")
                    }
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-2") }
                }

                fake.assertCaptured("\$exception").distinctId shouldBe "anon-2"
                fake.captured("\$exception").size shouldBe 1
            }
        }

        test("the new options come from the application config too; a wrong person profiles value stops the start") {
            PostHogFake().use { fake ->
                testApplication {
                    environment {
                        config =
                            fake.applicationConfig(
                                MapApplicationConfig(
                                    "posthog.personProfiles" to "identified_only",
                                    "posthog.customBlockedUserAgents" to "bot, crawler",
                                    "posthog.debug" to "false",
                                ),
                            )
                    }
                    application {
                        install(PostHog)
                        routing {
                            get("/") {
                                call.posthog.capture("viewed")
                                call.respondText("ok")
                            }
                        }
                    }
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                    client.get("/") {
                        header(DISTINCT_ID_HEADER, "anon-2")
                        header(HttpHeaders.UserAgent, "SomeCrawler/1.0")
                    }
                }

                fake.assertCaptured("viewed") { property("\$process_person_profile", false) }.distinctId shouldBe "anon-1"
                fake.captured("viewed").size shouldBe 1
            }

            PostHogFake().use { fake ->
                testApplication {
                    environment { config = fake.applicationConfig(MapApplicationConfig("posthog.optOut" to "true")) }
                    application {
                        install(PostHog)
                        posthog.isOptedOut shouldBe true
                    }
                    startApplication()
                }
            }

            shouldThrow<IllegalArgumentException> {
                testApplication {
                    environment { config = MapApplicationConfig("posthog.personProfiles" to "sometimes") }
                    application { install(PostHog) }
                    startApplication()
                }
            }.message shouldContain "identified_only"
        }

        test("the official SDK's options pass through: exposures, local evaluation, evaluation contexts, queue, error tracking") {
            PostHogFake().use { fake ->
                fake.flag("new-checkout", true)
                postHogTest(fake, {
                    install(PostHog) {
                        sendFeatureFlagEvent = false
                        secretKey = "phs_test"
                        localEvaluation = false
                        evaluationContexts += "web"
                        maxQueueSize = 500
                        maxBatchSize = 50
                        inAppIncludes += "io.github.slashncode"
                        captureUncaughtExceptions = true
                        releaseIdentifier = "shop@1.2.3"
                    }
                    routing {
                        get("/") {
                            call.respondText("${call.posthog.isFeatureEnabled("new-checkout")}")
                        }
                    }
                }) {
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }.bodyAsText() shouldBe "true"
                }

                // Without local evaluation the key does not stop PostHog from evaluating.
                val request = fake.flagRequests.single()
                request.toString() shouldContain "web"
                fake.assertNotCaptured("\$feature_flag_called")
            }
        }

        test("the EU cloud has a constant too") {
            POSTHOG_EU_HOST shouldBe "https://eu.i.posthog.com"
        }
    })
