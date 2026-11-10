package io.github.slashncode.posthog.ktor

import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AiTest :
    FunSpec({
        test("a generation in a call: model, messages, tokens, latency, for the call's person and session") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog)
                    routing {
                        get("/chat") {
                            call.posthog.ai.captureGeneration {
                                model = "gpt-5-mini"
                                provider = "openai"
                                input = listOf(PostHogAiMessage.system("Be brief."), PostHogAiMessage.user("Which umbrella?"))
                                output = listOf(PostHogAiMessage.assistant("The blue one."))
                                inputTokens = 42
                                outputTokens = 7
                                latency = 1.5.seconds
                                temperature = 0.2
                                stopReason = "stop"
                                sessionId = "conversation-1"
                                properties["feature"] = "shop-assistant"
                            }
                            call.posthog.ai.captureEmbedding {
                                model = "text-embedding-3-large"
                                provider = "openai"
                                input = "Which umbrella?"
                                inputTokens = 4
                            }
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/chat") {
                        header(DISTINCT_ID_HEADER, "anon-1")
                        header(SESSION_ID_HEADER, "session-1")
                    }
                }

                val generation =
                    fake.assertCaptured("\$ai_generation") {
                        property("\$ai_model", "gpt-5-mini")
                        property("\$ai_provider", "openai")
                        property("\$ai_input_tokens", 42)
                        property("\$ai_output_tokens", 7)
                        property("\$ai_latency", 1.5)
                        property("\$ai_temperature", 0.2)
                        property("\$ai_stop_reason", "stop")
                        property("\$ai_session_id", "conversation-1")
                        property("\$session_id", "session-1")
                        property("feature", "shop-assistant")
                    }
                generation.distinctId shouldBe "anon-1"
                generation.property("\$ai_trace_id") shouldNotBe null
                val input = generation.properties["\$ai_input"] as JsonArray
                input[1].jsonObject["role"]!!.jsonPrimitive.content shouldBe "user"
                input[1].jsonObject["content"]!!.jsonPrimitive.content shouldBe "Which umbrella?"
                (generation.properties["\$ai_output_choices"] as JsonArray)[0].jsonObject["content"]!!.jsonPrimitive.content shouldBe
                    "The blue one."
                fake.assertCaptured("\$ai_embedding") { property("\$ai_input", "Which umbrella?") }
            }
        }

        test("a trace with a span and generations inside: one trace id, parents, latency, output; a failing trace") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog)
                    val posthog = posthog
                    routing {
                        get("/chat") {
                            val answer =
                                call.posthog.ai.trace("answer", sessionId = "conversation-1", input = "Which umbrella?") {
                                    call.posthog.ai.span("search-products", input = "umbrella") { span ->
                                        delay(5.milliseconds)
                                        call.posthog.ai.captureGeneration { model = "rerank-1" }
                                        span.output = listOf("blue", "red")
                                    }
                                    call.posthog.ai.captureGeneration { model = "gpt-5-mini" }
                                    "The blue one."
                                }
                            shouldThrow<IllegalStateException> {
                                posthog.ai.trace("broken") { error("model down") }
                            }
                            call.respondText(answer)
                        }
                    }
                }) {
                    client.get("/chat") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                val trace = fake.assertCaptured("\$ai_trace") { property("\$ai_span_name", "answer") }
                val traceId = trace.property("\$ai_trace_id") as String
                trace.property("\$ai_input_state") shouldBe "Which umbrella?"
                trace.property("\$ai_output_state") shouldBe "The blue one."
                trace.property("\$ai_session_id") shouldBe "conversation-1"
                trace.property("\$ai_parent_id") shouldBe null
                (trace.property("\$ai_latency") as Double) shouldBeGreaterThan 0.0

                val span = fake.assertCaptured("\$ai_span") { property("\$ai_span_name", "search-products") }
                span.property("\$ai_trace_id") shouldBe traceId
                span.property("\$ai_parent_id") shouldBe traceId
                span.property("\$ai_session_id") shouldBe "conversation-1"
                (span.properties["\$ai_output_state"] as JsonArray).size shouldBe 2
                val spanId = span.property("\$ai_span_id") as String

                fake.assertCaptured("\$ai_generation") { property("\$ai_model", "rerank-1") }.let {
                    it.property("\$ai_trace_id") shouldBe traceId
                    it.property("\$ai_parent_id") shouldBe spanId
                }
                fake.assertCaptured("\$ai_generation") { property("\$ai_model", "gpt-5-mini") }.let {
                    it.property("\$ai_trace_id") shouldBe traceId
                    it.property("\$ai_parent_id") shouldBe traceId
                }
                fake.assertCaptured("\$ai_trace") { property("\$ai_span_name", "broken") }.let {
                    it.property("\$ai_is_error") shouldBe true
                    it.property("\$ai_error") shouldBe "model down"
                }
            }
        }

        test("privacy mode keeps prompts, outputs and states out; models and tokens still go") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog) { privacyMode = true }
                    routing {
                        get("/chat") {
                            call.posthog.ai.trace("answer", input = "secret question") {
                                call.posthog.ai.captureGeneration {
                                    model = "gpt-5-mini"
                                    input = listOf(PostHogAiMessage.user("secret question"))
                                    output = listOf(PostHogAiMessage.assistant("secret answer"))
                                    outputTokens = 3
                                }
                                "secret answer"
                            }
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/chat") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertCaptured("\$ai_generation") {
                    property("\$ai_model", "gpt-5-mini")
                    property("\$ai_output_tokens", 3)
                    property("\$ai_input", null)
                    property("\$ai_output_choices", null)
                }
                fake.assertCaptured("\$ai_trace") {
                    property("\$ai_input_state", null)
                    property("\$ai_output_state", null)
                }
            }
        }

        test("outside a call: the context's person; a generation outside any trace is a trace of its own") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog)
                    val posthog = posthog
                    routing {
                        get("/job") {
                            posthog.withContext(distinctId = "user-1", fresh = true) {
                                posthog.ai.captureGeneration { model = "a" }
                                posthog.ai.captureGeneration { model = "b" }
                                PostHogAiContext.current() shouldBe null
                                posthog.ai.trace {
                                    PostHogAiContext.current()!!.traceId shouldBe PostHogAiContext.currentThread()!!.traceId
                                }
                            }
                            posthog.ai.captureSpan(distinctId = "user-2") {
                                spanName = "tool"
                                error = "timeout"
                            }
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/job")
                }

                val a = fake.assertCaptured("\$ai_generation") { property("\$ai_model", "a") }
                val b = fake.assertCaptured("\$ai_generation") { property("\$ai_model", "b") }
                a.distinctId shouldBe "user-1"
                a.property("\$ai_trace_id") shouldNotBe b.property("\$ai_trace_id")
                fake
                    .assertCaptured("\$ai_span") {
                        property("\$ai_span_name", "tool")
                        property("\$ai_is_error", true)
                        property("\$ai_error", "timeout")
                    }.distinctId shouldBe "user-2"
            }
        }
    })
