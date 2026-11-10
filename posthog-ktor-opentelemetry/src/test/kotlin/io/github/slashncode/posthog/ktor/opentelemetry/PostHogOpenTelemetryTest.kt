package io.github.slashncode.posthog.ktor.opentelemetry

import io.github.slashncode.posthog.ktor.DISTINCT_ID_HEADER
import io.github.slashncode.posthog.ktor.PostHog
import io.github.slashncode.posthog.ktor.SESSION_ID_HEADER
import io.github.slashncode.posthog.ktor.posthog
import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

class PostHogOpenTelemetryTest :
    FunSpec({
        test("a call's spans and log lines go to PostHog with its person and session; AI spans to AI observability") {
            PostHogFake().use { fake ->
                testApplication {
                    environment { config = fake.applicationConfig() }
                    application {
                        install(PostHog)
                        install(PostHogOpenTelemetry) {
                            serviceName = "orders"
                            serviceVersion = "1.4.2"
                            environment = "test"
                            flushIntervalMs = 100
                        }
                        routing {
                            get("/orders/{id}") {
                                val id = call.parameters["id"]!!
                                call.application.posthogTracer.withSpan("load order") { span -> span.setAttribute("order.id", id) }
                                // An AI span as a model library writes it (gen_ai semantic conventions).
                                call.application.openTelemetry
                                    .getTracer("a-model-library")
                                    .spanBuilder("chat gpt-5-mini")
                                    .setAttribute("gen_ai.operation.name", "chat")
                                    .setAttribute("gen_ai.request.model", "gpt-5-mini")
                                    .setAttribute("gen_ai.input.messages", """[{"role":"user","content":"Which umbrella?"}]""")
                                    .startSpan()
                                    .end()
                                LoggerFactory.getLogger("orders").info("order {} loaded", id)
                                call.respondText("ok")
                            }
                        }
                    }
                    client.get("/orders/42") {
                        header(DISTINCT_ID_HEADER, "anon-1")
                        header(SESSION_ID_HEADER, "session-1")
                    }
                }

                val server =
                    fake.assertSpan("GET /orders/{id}") {
                        attribute("posthogDistinctId") shouldBe "anon-1"
                        attribute("sessionId") shouldBe "session-1"
                        resource["service.name"] shouldBe "orders"
                        resource["service.version"] shouldBe "1.4.2"
                        resource["deployment.environment.name"] shouldBe "test"
                        kind shouldBe 2
                    }
                fake.assertSpan("load order") {
                    parentSpanId shouldBe server.spanId
                    traceId shouldBe server.traceId
                    attribute("order.id") shouldBe "42"
                    attribute("posthogDistinctId") shouldBe "anon-1"
                }
                fake.assertAiSpan("chat gpt-5-mini") {
                    attribute("posthog.distinct_id") shouldBe "anon-1"
                    attribute("\$session_id") shouldBe "session-1"
                    attribute("\$current_url").toString() shouldStartWith "http://localhost/orders/42"
                    attribute("gen_ai.input.messages") shouldNotBe null
                }
                fake.aiSpans.map { it.name } shouldBe listOf("chat gpt-5-mini")
                fake.spans.single { it.name == "chat gpt-5-mini" }.attribute("posthog.distinct_id") shouldBe null
                fake.assertLog("order 42 loaded") {
                    attribute("posthogDistinctId") shouldBe "anon-1"
                    attribute("sessionId") shouldBe "session-1"
                    severityText shouldBe "INFO"
                    traceId shouldBe server.traceId
                }
            }
        }

        test("privacy mode, a span that names its person, beforeSpanSend, and spans that fail") {
            PostHogFake().use { fake ->
                testApplication {
                    environment { config = fake.applicationConfig() }
                    application {
                        install(PostHog) { privacyMode = true }
                        install(PostHogOpenTelemetry) {
                            flushIntervalMs = 100
                            logs = false
                            beforeSpanSend { span -> if (span.name == "health") null else span }
                        }
                        routing {
                            get("/") {
                                val tracer = call.application.openTelemetry.getTracer("a-model-library")
                                tracer
                                    .spanBuilder("chat gpt-5-mini")
                                    .setAttribute("gen_ai.operation.name", "chat")
                                    .setAttribute("gen_ai.input.messages", "secret")
                                    .setAttribute("gen_ai.output.messages", "secret")
                                    .setAttribute("gen_ai.usage.output_tokens", 7L)
                                    .setAttribute("posthog.distinct_id", "user-from-span")
                                    .startSpan()
                                    .apply {
                                        addEvent("gen_ai.content.prompt", Attributes.empty())
                                        recordException(IllegalStateException("secret error"))
                                    }.end()
                                tracer.spanBuilder("health").startSpan().end()
                                shouldThrow<IllegalStateException> {
                                    call.application.posthogTracer.withSpan("charge card") { error("declined") }
                                }
                                call.respondText("ok")
                            }
                        }
                    }
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertAiSpan("chat gpt-5-mini") {
                    attribute("posthog.distinct_id") shouldBe "user-from-span"
                    attribute("gen_ai.input.messages") shouldBe null
                    attribute("gen_ai.output.messages") shouldBe null
                    attribute("gen_ai.usage.output_tokens") shouldBe 7L
                    events shouldBe listOf("exception")
                }
                fake.assertSpan("charge card") { statusCode shouldBe 2 }
                fake.spans.none { it.name == "health" } shouldBe true
                fake.logs shouldBe emptyList()
            }
        }

        test("an OpenTelemetry SDK built elsewhere (a model library's own) sends its AI spans with aiSpanProcessor") {
            PostHogFake().use { fake ->
                testApplication {
                    environment { config = fake.applicationConfig() }
                    application {
                        install(PostHog)
                        val processor = posthog.aiSpanProcessor(flushInterval = Duration.ofMillis(100))!!
                        val tracerProvider = SdkTracerProvider.builder().addSpanProcessor(processor).build()
                        routing {
                            get("/") {
                                tracerProvider
                                    .get("koog")
                                    .spanBuilder("embeddings text-embedding-3-large")
                                    .setAttribute("gen_ai.operation.name", "embeddings")
                                    .startSpan()
                                    .end()
                                tracerProvider
                                    .get("koog")
                                    .spanBuilder("not ai")
                                    .startSpan()
                                    .end()
                                tracerProvider.forceFlush().join(5, TimeUnit.SECONDS)
                                call.respondText("ok")
                            }
                        }
                    }
                    client.get("/") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertAiSpan("embeddings text-embedding-3-large") { attribute("posthog.distinct_id") shouldBe "anon-1" }
                fake.aiSpans.size shouldBe 1
            }
        }

        test("an HttpClient passes the trace on; without a project token everything is a no-op") {
            PostHogFake().use { fake ->
                var traceparent: String? = null
                testApplication {
                    environment { config = fake.applicationConfig() }
                    application {
                        install(PostHog)
                        install(PostHogOpenTelemetry) { flushIntervalMs = 100 }
                        val otel = openTelemetry
                        val downstream =
                            HttpClient(
                                MockEngine { request ->
                                    traceparent = request.headers["traceparent"]
                                    respondOk()
                                },
                            ) { posthogOpenTelemetry(otel) }
                        routing {
                            get("/") {
                                downstream.get("http://orders.internal/orders")
                                call.respondText("ok")
                            }
                        }
                    }
                    client.get("/")
                }
                val server = fake.assertSpan("GET")
                traceparent!! shouldStartWith "00-${server.traceId}-"
            }

            testApplication {
                application {
                    install(PostHog)
                    install(PostHogOpenTelemetry)
                    openTelemetry shouldBe OpenTelemetry.noop()
                }
                startApplication()
            }

            shouldThrow<IllegalStateException> {
                testApplication {
                    application { install(PostHogOpenTelemetry) }
                    startApplication()
                }
            }
        }
    })
