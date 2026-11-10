package io.github.slashncode.posthog.ktor.langchain4j

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.ModelProvider
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.listener.ChatModelListener
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ChatRequestParameters
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.output.FinishReason
import dev.langchain4j.model.output.TokenUsage
import io.github.slashncode.posthog.ktor.DISTINCT_ID_HEADER
import io.github.slashncode.posthog.ktor.PostHog
import io.github.slashncode.posthog.ktor.PostHogClient
import io.github.slashncode.posthog.ktor.SESSION_ID_HEADER
import io.github.slashncode.posthog.ktor.ai
import io.github.slashncode.posthog.ktor.posthog
import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// A chat model as a provider integration would be, answering from the test.
private class ScriptedModel(
    private val listener: ChatModelListener,
    private val answer: (ChatRequest) -> ChatResponse,
) : ChatModel {
    override fun doChat(chatRequest: ChatRequest): ChatResponse {
        Thread.sleep(5)
        return answer(chatRequest)
    }

    override fun listeners(): List<ChatModelListener> = listOf(listener)

    override fun provider(): ModelProvider = ModelProvider.OPEN_AI
}

private fun model(
    posthog: PostHogClient,
    answer: (ChatRequest) -> ChatResponse,
) = ScriptedModel(PostHogChatModelListener(posthog, mapOf("feature" to "shop-assistant")), answer)

class PostHogChatModelListenerTest :
    FunSpec({
        test("a chat model call is a generation of the call's person, in the surrounding AI trace") {
            PostHogFake().use { fake ->
                testApplication {
                    environment { config = fake.applicationConfig() }
                    application {
                        install(PostHog)
                        val chat =
                            model(posthog) {
                                ChatResponse
                                    .builder()
                                    .aiMessage(
                                        AiMessage.from(
                                            "Let me look.",
                                            listOf(
                                                ToolExecutionRequest
                                                    .builder()
                                                    .id(
                                                        "call-1",
                                                    ).name("searchProducts")
                                                    .arguments("""{"q":"umbrella"}""")
                                                    .build(),
                                            ),
                                        ),
                                    ).modelName("gpt-5-mini-2026-08-07")
                                    .tokenUsage(TokenUsage(42, 7))
                                    .finishReason(FinishReason.TOOL_EXECUTION)
                                    .build()
                            }
                        val posthog = posthog
                        routing {
                            get("/chat") {
                                posthog.ai.trace("answer", sessionId = "conversation-1") {
                                    // Blocking model calls belong on an IO thread; the context goes along.
                                    withContext(Dispatchers.IO) {
                                        chat.chat(
                                            ChatRequest
                                                .builder()
                                                .messages(SystemMessage.from("Be brief."), UserMessage.from("Which umbrella?"))
                                                .parameters(
                                                    ChatRequestParameters
                                                        .builder()
                                                        .modelName("gpt-5-mini")
                                                        .temperature(0.2)
                                                        .maxOutputTokens(200)
                                                        .toolSpecifications(
                                                            ToolSpecification
                                                                .builder()
                                                                .name(
                                                                    "searchProducts",
                                                                ).description("Finds products")
                                                                .build(),
                                                        ).build(),
                                                ).build(),
                                        )
                                    }
                                }
                                call.respondText("ok")
                            }
                        }
                    }
                    client.get("/chat") {
                        header(DISTINCT_ID_HEADER, "anon-1")
                        header(SESSION_ID_HEADER, "session-1")
                    }
                }

                val trace = fake.assertCaptured("\$ai_trace") { property("\$ai_span_name", "answer") }
                val generation =
                    fake.assertCaptured("\$ai_generation") {
                        property("\$ai_model", "gpt-5-mini-2026-08-07")
                        property("\$ai_provider", "openai")
                        property("\$ai_input_tokens", 42)
                        property("\$ai_output_tokens", 7)
                        property("\$ai_temperature", 0.2)
                        property("\$ai_max_tokens", 200)
                        property("\$ai_stop_reason", "tool_execution")
                        property("\$ai_session_id", "conversation-1")
                        property("\$session_id", "session-1")
                        property("feature", "shop-assistant")
                    }
                generation.distinctId shouldBe "anon-1"
                generation.property("\$ai_trace_id") shouldBe trace.property("\$ai_trace_id")
                generation.property("\$ai_parent_id") shouldBe trace.property("\$ai_trace_id")
                (generation.property("\$ai_latency") as Double) shouldBeGreaterThan 0.0
                val input = generation.properties["\$ai_input"] as JsonArray
                input.map { it.jsonObject["role"]!!.jsonPrimitive.content } shouldBe listOf("system", "user")
                val answer = (generation.properties["\$ai_output_choices"] as JsonArray).single().jsonObject
                answer["content"]!!.jsonPrimitive.content shouldBe "Let me look."
                answer["tool_calls"]!!
                    .jsonArray
                    .single()
                    .jsonObject["function"]!!
                    .jsonObject["name"]!!
                    .jsonPrimitive.content shouldBe "searchProducts"
                (generation.properties["\$ai_tools"] as JsonArray).size shouldBe 1
            }
        }

        test("a failing call is a failed generation; the model's error still reaches the caller") {
            PostHogFake().use { fake ->
                testApplication {
                    environment { config = fake.applicationConfig() }
                    application {
                        install(PostHog)
                        val chat = model(posthog) { error("rate limited") }
                        routing {
                            get("/chat") {
                                shouldThrow<IllegalStateException> { chat.chat("Which umbrella?") }
                                call.respondText("ok")
                            }
                        }
                    }
                    client.get("/chat") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake
                    .assertCaptured("\$ai_generation") {
                        property("\$ai_is_error", true)
                        property("\$ai_error", "rate limited")
                        property("\$ai_provider", "openai")
                    }.distinctId shouldBe "anon-1"
            }
        }

        test("privacy mode: no messages") {
            PostHogFake().use { fake ->
                testApplication {
                    environment { config = fake.applicationConfig() }
                    application {
                        install(PostHog) { privacyMode = true }
                        val chat = model(posthog) { ChatResponse.builder().aiMessage(AiMessage.from("secret")).build() }
                        routing {
                            get("/chat") { call.respondText(chat.chat("secret question")) }
                        }
                    }
                    client.get("/chat") { header(DISTINCT_ID_HEADER, "anon-1") }
                }

                fake.assertCaptured("\$ai_generation") {
                    property("\$ai_input", null)
                    property("\$ai_output_choices", null)
                }
            }
        }
    })
