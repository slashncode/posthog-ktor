package io.github.slashncode.posthog.ktor.langchain4j

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.ModelProvider
import dev.langchain4j.model.chat.listener.ChatModelErrorContext
import dev.langchain4j.model.chat.listener.ChatModelListener
import dev.langchain4j.model.chat.listener.ChatModelRequestContext
import dev.langchain4j.model.chat.listener.ChatModelResponseContext
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import io.github.slashncode.posthog.ktor.PostHogAiContext
import io.github.slashncode.posthog.ktor.PostHogAiMessage
import io.github.slashncode.posthog.ktor.PostHogClient
import io.github.slashncode.posthog.ktor.PostHogContext
import io.github.slashncode.posthog.ktor.ai
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Captures every call of a LangChain4j chat model as a PostHog AI generation (`$ai_generation`):
 * model, provider, messages, answer and tool calls, tokens, latency, temperature, token limit, tools
 * offered, finish reason, and errors. The generation belongs to the person, session and properties of
 * the call or [PostHogContext] the model was called in, and to the surrounding `posthog.ai.trace { }`,
 * also for streaming models that answer on another thread. With the `privacyMode` option, messages
 * stay behind.
 *
 * ```kotlin
 * val model = OpenAiChatModel.builder()
 *     .apiKey(key)
 *     .modelName("gpt-5-mini")
 *     .listeners(listOf(PostHogChatModelListener(application.posthog)))
 *     .build()
 * ```
 *
 * @param properties added to every generation, e.g. which feature asked.
 */
public class PostHogChatModelListener(
    private val posthog: PostHogClient,
    private val properties: Map<String, Any?> = emptyMap(),
) : ChatModelListener {
    override fun onRequest(context: ChatModelRequestContext) {
        context.attributes()[STARTED] =
            Started(System.nanoTime(), PostHogContext.currentThread()?.snapshot(), PostHogAiContext.currentThread())
    }

    override fun onResponse(context: ChatModelResponseContext) {
        capture(context.attributes()[STARTED] as? Started, context.chatRequest(), context.modelProvider(), context.chatResponse(), null)
    }

    override fun onError(context: ChatModelErrorContext) {
        capture(context.attributes()[STARTED] as? Started, context.chatRequest(), context.modelProvider(), null, context.error())
    }

    // A listener must never fail the model call.
    private fun capture(
        started: Started?,
        request: ChatRequest,
        modelProvider: ModelProvider?,
        response: ChatResponse?,
        failure: Throwable?,
    ) {
        try {
            val parameters = request.parameters()
            posthog.ai.captureGeneration(distinctId = started?.context?.distinctId) {
                started?.context?.let { context ->
                    properties.putAll(context.properties)
                    context.sessionId?.let { properties[SESSION_ID] = it }
                }
                started?.trace?.let { trace ->
                    traceId = trace.traceId
                    parentId = trace.spanId
                    sessionId = trace.sessionId
                }
                properties.putAll(this@PostHogChatModelListener.properties)
                model = response?.modelName() ?: parameters?.modelName()
                provider = modelProvider?.let(::providerName)
                input = request.messages().map(::message)
                output = response?.aiMessage()?.let { listOf(message(it)) }
                inputTokens = response?.tokenUsage()?.inputTokenCount()
                outputTokens = response?.tokenUsage()?.outputTokenCount()
                latency = started?.let { (System.nanoTime() - it.at).nanoseconds }
                temperature = parameters?.temperature()
                maxTokens = parameters?.maxOutputTokens()
                tools =
                    parameters?.toolSpecifications()?.takeIf { it.isNotEmpty() }?.map { tool ->
                        mapOf("type" to "function", "function" to mapOf("name" to tool.name(), "description" to tool.description()))
                    }
                stopReason = response?.finishReason()?.name?.lowercase()
                error = failure
            }
        } catch (e: Exception) {
            log.warn("Could not capture a LangChain4j generation for PostHog", e)
        }
    }

    private class Started(
        val at: Long,
        val context: PostHogContext?,
        val trace: PostHogAiContext?,
    )

    private companion object {
        val STARTED = Any()
        const val SESSION_ID = "\$session_id"
        val log = LoggerFactory.getLogger(PostHogChatModelListener::class.java)!!

        fun message(message: ChatMessage): PostHogAiMessage =
            when (message) {
                is SystemMessage -> PostHogAiMessage("system", message.text())
                is UserMessage ->
                    PostHogAiMessage(
                        "user",
                        if (message.hasSingleText()) message.singleText() else message.contents().map(::part),
                    )
                is AiMessage ->
                    PostHogAiMessage(
                        "assistant",
                        message.text(),
                        message
                            .toolExecutionRequests()
                            .takeIf { it.isNotEmpty() }
                            ?.let { requests ->
                                mapOf(
                                    "tool_calls" to
                                        requests.map {
                                            mapOf(
                                                "id" to it.id(),
                                                "type" to "function",
                                                "function" to mapOf("name" to it.name(), "arguments" to it.arguments()),
                                            )
                                        },
                                )
                            }.orEmpty(),
                    )
                is ToolExecutionResultMessage ->
                    PostHogAiMessage(
                        "tool",
                        message.text(),
                        mapOf(
                            "tool_call_id" to message.id(),
                            "name" to message.toolName(),
                        ),
                    )
                else -> PostHogAiMessage(message.type().name.lowercase(), message.toString())
            }

        // Images and other media go as their type only: no base64 blobs in events.
        fun part(content: dev.langchain4j.data.message.Content): Map<String, Any?> =
            when (content) {
                is TextContent -> mapOf("type" to "text", "text" to content.text())
                else -> mapOf("type" to content.type().name.lowercase())
            }

        // PostHog's names for the providers, as its own SDKs send them.
        fun providerName(provider: ModelProvider): String =
            when (provider.name) {
                "OPEN_AI" -> "openai"
                "AZURE_OPEN_AI", "MICROSOFT_FOUNDRY" -> "azure"
                "ANTHROPIC", "GOOGLE_VERTEX_AI_ANTHROPIC" -> "anthropic"
                "GOOGLE_AI_GEMINI", "GOOGLE_GENAI" -> "gemini"
                "GOOGLE_VERTEX_AI_GEMINI" -> "vertex"
                "AMAZON_BEDROCK" -> "bedrock"
                "MISTRAL_AI" -> "mistral"
                else -> provider.name.lowercase()
            }
    }
}
