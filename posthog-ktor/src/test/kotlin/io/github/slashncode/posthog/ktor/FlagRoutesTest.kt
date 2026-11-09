package io.github.slashncode.posthog.ktor

import io.github.slashncode.posthog.ktor.FakePostHog.Flag
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.principal
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

private val flags =
    mapOf(
        "new-checkout" to Flag(true),
        "checkout-copy" to Flag("calm", """{"button":"Nichts bestellen"}"""),
        "coach-placement" to Flag(false),
        "deal-countdown" to Flag("until"),
    )

private val basicAuth = "Basic " + Base64.getEncoder().encodeToString("ada:secret".toByteArray())

private fun FakePostHog.exposures() =
    events().filter { it.string("event") == "\$feature_flag_called" }.map {
        Triple(it.string("distinct_id"), it.property("\$feature_flag"), it.property("\$feature_flag_response"))
    }

class FlagRoutesTest :
    FunSpec({
        test("routes behind a flag exist only while it is on, for the variant asked for, after authentication") {
            FakePostHog().use { fake ->
                fake.remoteFlags = flags
                postHogTest(fake, {
                    install(Authentication) { basic("basic") { validate { UserIdPrincipal(it.name) } } }
                    install(PostHog) {
                        resolveDistinctId { it.principal<UserIdPrincipal>()?.name }
                        sdk { featureFlagCacheMaxAgeMs(0) } // every evaluation asks the fake
                    }
                    routing {
                        get("/health") { call.respondText("ok") }
                        featureFlag("new-checkout") {
                            get("/checkout") {
                                call.posthog.capture("checkout_viewed")
                                call.respondText("new")
                            }
                        }
                        featureFlag("coach-placement") { get("/coach") { call.respondText("coach") } }
                        featureFlag("checkout-copy", variant = "calm") { get("/copy/calm") { call.respondText("calm") } }
                        featureFlag("checkout-copy", variant = "loud", onDisabled = { it.respondRedirect("/copy/calm") }) {
                            get("/copy/loud") { call.respondText("loud") }
                        }
                        authenticate("basic") {
                            featureFlag("new-checkout") { get("/account/checkout") { call.respondText(call.posthog.distinctId!!) } }
                        }
                    }
                }) {
                    val client = createClient { followRedirects = false }

                    suspend fun get(path: String) = client.get(path) { header(DISTINCT_ID_HEADER, "anon-1") }

                    get("/health").bodyAsText() shouldBe "ok"
                    fake.flagRequests().size shouldBe 0

                    get("/checkout").bodyAsText() shouldBe "new"
                    get("/coach").status shouldBe HttpStatusCode.NotFound
                    get("/copy/calm").bodyAsText() shouldBe "calm"
                    get("/copy/loud").headers[HttpHeaders.Location] shouldBe "/copy/calm"
                    get("/account/checkout").status shouldBe HttpStatusCode.Unauthorized
                    client
                        .get("/account/checkout") {
                            header(DISTINCT_ID_HEADER, "anon-1")
                            header(HttpHeaders.Authorization, basicAuth)
                        }.bodyAsText() shouldBe "ada"
                }

                // One evaluation per call that reached a gate; the protected one for the principal.
                fake.flagRequests().map { it.string("distinct_id") } shouldBe listOf("anon-1", "anon-1", "anon-1", "anon-1", "ada")
                eventually(10.seconds) { fake.exposures().size shouldBe 4 }
                fake.exposures() shouldContainAll
                    listOf(
                        Triple("anon-1", "new-checkout", "true"),
                        Triple("anon-1", "coach-placement", "false"),
                        Triple("anon-1", "checkout-copy", "calm"),
                        Triple("ada", "new-checkout", "true"),
                    )
                fake.events().single { it.string("event") == "checkout_viewed" }.property("\$route") shouldBe "/checkout"
            }
        }

        test("the bootstrap route answers the flags asked for with their payloads, without an exposure, never cached") {
            FakePostHog().use { fake ->
                fake.remoteFlags = flags
                postHogTest(fake, {
                    install(PostHog)
                    routing { posthogFlags("/flags", listOf("new-checkout", "checkout-copy", "coach-placement", "unknown")) }
                }) {
                    val response = client.get("/flags") { header(DISTINCT_ID_HEADER, "anon-1") }
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    Json.parseToJsonElement(response.bodyAsText()) shouldBe
                        Json.parseToJsonElement(
                            """
                            {"featureFlags":{"new-checkout":true,"checkout-copy":"calm"},
                             "featureFlagPayloads":{"checkout-copy":{"button":"Nichts bestellen"}}}
                            """,
                        )

                    client.get("/flags").bodyAsText() shouldBe """{"featureFlags":{},"featureFlagPayloads":{}}"""

                    // posthog-js' cookie stands in for the header (navigations, fetches without tracing headers).
                    val cookie = """{"distinct_id":"cookie-1","${'$'}sesid":[1,"s",1]}""".encodeURLParameter()
                    client.get("/flags") { header(HttpHeaders.Cookie, "ph_phc_test_posthog=$cookie") }
                    fake.flagRequests().last().string("distinct_id") shouldBe "cookie-1"
                }

                delay(500)
                fake.exposures() shouldBe emptyList()
            }
        }

        test("a server-rendered page's bootstrap: the call's id, else a minted one the call captures with; script-safe") {
            FakePostHog().use { fake ->
                fake.remoteFlags = mapOf("new-checkout" to Flag(true, """{"note":"</script><script>alert(1)</script>"}"""))
                postHogTest(fake, {
                    install(PostHog) { resolveDistinctId { it.request.headers["X-User"] } }
                    routing {
                        get("/page") {
                            val mint = call.request.queryParameters["mint"] == "true"
                            val bootstrap = call.posthog.bootstrap(listOf("new-checkout"), mintDistinctId = mint)
                            call.posthog.capture("page_rendered")
                            call.respondText(bootstrap.toJson())
                        }
                    }
                }) {
                    val minted = client.get("/page?mint=true").bodyAsText()
                    minted shouldNotContain "<"
                    val json = Json.parseToJsonElement(minted).jsonObject
                    val id = json["distinctID"]!!.jsonPrimitive.content
                    id[14] shouldBe '7' // a UUIDv7, like posthog-js' own
                    json["isIdentifiedID"]!!.jsonPrimitive.content shouldBe "false"
                    json["featureFlags"]!!.jsonObject["new-checkout"]!!.jsonPrimitive.content shouldBe "true"
                    json["featureFlagPayloads"]!!
                        .jsonObject["new-checkout"]!!
                        .jsonObject["note"]!!
                        .jsonPrimitive.content shouldBe
                        "</script><script>alert(1)</script>"
                    eventually(10.seconds) { fake.events().any { it.string("event") == "page_rendered" } shouldBe true }
                    fake.events().single { it.string("event") == "page_rendered" }.string("distinct_id") shouldBe id

                    client.get("/page").bodyAsText() shouldBe """{"featureFlags":{},"featureFlagPayloads":{}}"""

                    val user = Json.parseToJsonElement(client.get("/page") { header("X-User", "user-1") }.bodyAsText()).jsonObject
                    user["distinctID"]!!.jsonPrimitive.content shouldBe "user-1"
                    user["isIdentifiedID"]!!.jsonPrimitive.content shouldBe "true"
                }
            }
        }
    })
