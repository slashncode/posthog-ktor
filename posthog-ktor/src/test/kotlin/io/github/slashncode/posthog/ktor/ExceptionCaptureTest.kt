package io.github.slashncode.posthog.ktor

import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

private class DishMissing : RuntimeException("no such dish")

private fun Application.failingRoutes() {
    routing {
        get("/boom/{id}") { error("broken") }
        get("/missing") { throw DishMissing() }
        get("/bad") { throw BadRequestException("no") }
        get("/flush") {
            call.application.posthog.flush()
            call.respond("flushed")
        }
    }
}

private fun PostHogFake.exceptions() = sent().filter { it.string("event") == "\$exception" }

class ExceptionCaptureTest :
    FunSpec({
        test("a failed call goes to error tracking with its ids and request properties") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog)
                    failingRoutes()
                }) {
                    client
                        .get("/boom/7") {
                            header(DISTINCT_ID_HEADER, "anon-1")
                            header(SESSION_ID_HEADER, "session-1")
                        }.status shouldBe HttpStatusCode.InternalServerError
                    // Reported when the call ends, not only when the application stops.
                    client.get("/flush")
                    eventually(10.seconds) { fake.exceptions().size shouldBe 1 }
                }

                val exception = fake.exceptions().single()
                exception.string("distinct_id") shouldBe "anon-1"
                exception.property("\$session_id") shouldBe "session-1"
                exception.property("\$route") shouldBe "/boom/{id}"
                exception.property("\$request_path") shouldBe "/boom/7"
            }
        }

        test("only server errors by default: client errors, StatusPages' 4xx and excluded exceptions stay out") {
            for (statusPagesFirst in listOf(true, false)) {
                PostHogFake().use { fake ->
                    postHogTest(fake, {
                        val statusPages: Application.() -> Unit = {
                            install(StatusPages) { exception<DishMissing> { call, _ -> call.respond(HttpStatusCode.NotFound) } }
                        }
                        if (statusPagesFirst) statusPages()
                        install(PostHog)
                        if (!statusPagesFirst) statusPages()
                        failingRoutes()
                    }) {
                        client.get("/missing").status shouldBe HttpStatusCode.NotFound
                        client.get("/bad").status shouldBe HttpStatusCode.BadRequest
                        client.get("/boom/1").status shouldBe HttpStatusCode.InternalServerError
                    }

                    eventually(10.seconds) { fake.exceptions().size shouldBe 1 }
                    delay(300)
                    withClue("StatusPages installed ${if (statusPagesFirst) "before" else "after"} PostHog") {
                        fake.exceptions().single().property("\$request_path") shouldBe "/boom/1"
                    }
                }
            }
        }

        test("what is reported is configurable: statuses, exclusions, a filter, or nothing") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(StatusPages) { exception<DishMissing> { call, _ -> call.respond(HttpStatusCode.NotFound) } }
                    install(PostHog) {
                        captureStatus { it.value >= 400 }
                        excludedExceptions.clear()
                        exceptionFilter { cause, _ -> cause.message != "broken" }
                    }
                    failingRoutes()
                }) {
                    client.get("/missing")
                    client.get("/bad")
                    client.get("/boom/1")
                }

                eventually(10.seconds) { fake.exceptions().size shouldBe 2 }
                delay(300)
                fake.exceptions().map { it.property("\$request_path") }.toSet() shouldBe setOf("/missing", "/bad")
            }

            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog) { captureExceptions = false }
                    failingRoutes()
                    routing { get("/ok") { call.respond("ok") } }
                }) {
                    client.get("/boom/1")
                    client.get("/ok")
                }
                delay(500)
                fake.exceptions().size shouldBe 0
            }
        }
    })
