package io.github.slashncode.posthog.ktor.opentelemetry

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.agents.testing.tools.mockLLMAnswer
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import io.github.slashncode.posthog.ktor.DISTINCT_ID_HEADER
import io.github.slashncode.posthog.ktor.PostHog
import io.github.slashncode.posthog.ktor.posthog
import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class KoogTest :
    FunSpec({
        test("a Koog agent's model calls reach AI observability through its OpenTelemetry feature, for the call's person") {
            PostHogFake().use { fake ->
                testApplication {
                    environment { config = fake.applicationConfig() }
                    application {
                        install(PostHog)
                        val posthog = posthog
                        routing {
                            get("/ask") {
                                val agent =
                                    AIAgent(
                                        promptExecutor = getMockExecutor { mockLLMAnswer("The blue one.").asDefaultResponse },
                                        llmModel = OpenAIModels.Chat.GPT4oMini,
                                        systemPrompt = "Be brief.",
                                    ) {
                                        install(OpenTelemetry) {
                                            addSpanExporter(posthog.aiSpanExporter()!!)
                                            // An exporter cannot see the call: the agent names its person.
                                            addResourceAttributes(mapOf("posthog.distinct_id" to call.posthog.distinctId!!))
                                        }
                                    }
                                call.respondText(agent.run("Which umbrella?"))
                            }
                        }
                    }
                    client.get("/ask") { header(DISTINCT_ID_HEADER, "anon-1") }.status.value shouldBe 200
                }

                val generation =
                    fake.assertAiSpan("chat gpt-4o-mini") {
                        attribute("gen_ai.operation.name") shouldBe "chat"
                    }
                generation.resource["posthog.distinct_id"] shouldBe "anon-1"
                generation.attribute("gen_ai.request.model") shouldNotBe null
                fake.aiSpans.all { span ->
                    span.attributes.keys.any { it.startsWith("gen_ai.") } || span.name.startsWith("gen_ai.")
                } shouldBe
                    true
            }
        }
    })
