package io.github.slashncode.posthog.ktor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * PostHog AI observability (LLM analytics): generations, embeddings, spans and traces, captured as
 * PostHog's SDKs capture them (`$ai_generation`, `$ai_embedding`, `$ai_span`, `$ai_trace`), for the
 * call's or the context's person and session, so a conversation links to its session replay.
 *
 * Inside [trace] and [span], events take their trace id and parent from the block; outside one, each
 * generation is a trace of its own. With the `privacyMode` option, prompts, outputs and states stay
 * behind. `posthog-ktor-langchain4j` captures LangChain4j's chat models this way; libraries that
 * emit OpenTelemetry `gen_ai` spans (Koog, Spring AI) go through `posthog-ktor-opentelemetry`.
 */
public class PostHogAi internal constructor(
    private val client: PostHogClient,
    private val call: PostHogCall?,
) {
    /**
     * Captures one call of a language model (`$ai_generation`).
     *
     * ```kotlin
     * call.posthog.ai.captureGeneration {
     *     model = "gpt-5-mini"
     *     provider = "openai"
     *     input = listOf(PostHogAiMessage.user(question))
     *     output = listOf(PostHogAiMessage.assistant(answer))
     *     inputTokens = 42
     *     outputTokens = 120
     *     latency = 1.4.seconds
     * }
     * ```
     */
    public fun captureGeneration(
        distinctId: String? = null,
        build: PostHogAiGeneration.() -> Unit,
    ) {
        capture(GENERATION, PostHogAiGeneration().apply(build), distinctId)
    }

    /** Captures one call of an embedding model (`$ai_embedding`). */
    public fun captureEmbedding(
        distinctId: String? = null,
        build: PostHogAiEmbedding.() -> Unit,
    ) {
        capture(EMBEDDING, PostHogAiEmbedding().apply(build), distinctId)
    }

    /** Captures a step that is not a model call (`$ai_span`): a tool call, a retrieval, a guardrail. */
    public fun captureSpan(
        distinctId: String? = null,
        build: PostHogAiSpan.() -> Unit,
    ) {
        capture(SPAN, PostHogAiSpan().apply(build), distinctId)
    }

    /** Captures a whole interaction (`$ai_trace`); [trace] does it for a block. */
    public fun captureTrace(
        distinctId: String? = null,
        build: PostHogAiTrace.() -> Unit,
    ) {
        capture(TRACE, PostHogAiTrace().apply(build), distinctId)
    }

    /**
     * Runs [block] as one AI trace: the generations, embeddings and spans captured inside share its
     * trace id, and it is captured as `$ai_trace` at the end with its latency, [input], the output
     * (the scope's `output`, else the block's result) and its error if it fails. [sessionId] groups
     * the traces of a conversation (`$ai_session_id`).
     */
    public suspend fun <T> trace(
        name: String? = null,
        sessionId: String? = null,
        input: Any? = null,
        block: suspend CoroutineScope.(PostHogAiScope) -> T,
    ): T {
        val parent = currentCoroutineContext()[PostHogAiContext]
        val traceId = newAiId()
        val session = sessionId ?: parent?.sessionId
        return measured(PostHogAiContext(traceId, traceId, session), block) { scope, latency, result, error ->
            captureTrace {
                this.traceId = traceId
                this.sessionId = session
                spanName = name
                inputState = input
                outputState = scope.output ?: result.takeUnless { it is Unit }
                this.latency = latency
                this.error = error
            }
        }
    }

    /**
     * Runs [block] as a span of the current trace (a new trace outside one): events inside get it as
     * their parent, and it is captured as `$ai_span` at the end with its latency, [input], output and
     * error.
     */
    public suspend fun <T> span(
        name: String,
        input: Any? = null,
        block: suspend CoroutineScope.(PostHogAiScope) -> T,
    ): T {
        val parent = currentCoroutineContext()[PostHogAiContext]
        val traceId = parent?.traceId ?: newAiId()
        val spanId = newAiId()
        return measured(PostHogAiContext(traceId, spanId, parent?.sessionId), block) { scope, latency, result, error ->
            captureSpan {
                this.traceId = traceId
                this.spanId = spanId
                parentId = parent?.spanId ?: traceId
                spanName = name
                inputState = input
                outputState = scope.output ?: result.takeUnless { it is Unit }
                this.latency = latency
                this.error = error
            }
        }
    }

    private suspend fun <T> measured(
        context: PostHogAiContext,
        block: suspend CoroutineScope.(PostHogAiScope) -> T,
        capture: (PostHogAiScope, Duration, Any?, Throwable?) -> Unit,
    ): T {
        val scope = PostHogAiScope(context.traceId, context.spanId)
        val start = System.nanoTime()
        val result =
            try {
                withContext(context) { block(scope) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                capture(scope, (System.nanoTime() - start).nanoseconds, null, e)
                throw e
            }
        capture(scope, (System.nanoTime() - start).nanoseconds, result, null)
        return result
    }

    private fun capture(
        event: String,
        values: PostHogAiEvent,
        distinctId: String?,
    ) {
        val context = PostHogAiContext.currentThread()
        val properties =
            values.toProperties(
                defaultTraceId = context?.traceId ?: newAiId(),
                defaultParentId = context?.spanId?.takeIf { values !is PostHogAiTrace },
                defaultSessionId = context?.sessionId,
                privacyMode = client.privacyMode,
            )
        if (call != null) call.capture(event, properties, distinctId) else client.capture(event, properties, distinctId)
    }

    private companion object {
        const val GENERATION = "\$ai_generation"
        const val EMBEDDING = "\$ai_embedding"
        const val SPAN = "\$ai_span"
        const val TRACE = "\$ai_trace"
    }
}

/** AI observability for the application: see [PostHogAi]. */
public val PostHogClient.ai: PostHogAi get() = PostHogAi(this, null)

/** AI observability for this call's person and session: see [PostHogAi]. */
public val PostHogCall.ai: PostHogAi get() = PostHogAi(client, this)

/** The trace or span [PostHogAi.trace] and [PostHogAi.span] run a block in. */
public class PostHogAiScope internal constructor(
    /** The trace's id (`$ai_trace_id`). */
    public val traceId: String,
    /** This trace's or span's own id, the parent of the events captured inside. */
    public val spanId: String,
) {
    /** The output captured as `$ai_output_state`; the block's result if not set. */
    public var output: Any? = null
}

/**
 * The AI trace a coroutine runs in: [PostHogAi.trace] and [PostHogAi.span] put one into the
 * coroutine context, and AI events captured inside take their trace id, parent and session from it,
 * also from code that cannot suspend (a model library's callback on the same thread).
 */
public class PostHogAiContext internal constructor(
    /** The trace id (`$ai_trace_id`). */
    public val traceId: String,
    /** The id of the innermost trace or span, the parent of new events (`$ai_parent_id`). */
    public val spanId: String,
    /** The conversation the trace belongs to (`$ai_session_id`), if any. */
    public val sessionId: String?,
) : AbstractCoroutineContextElement(PostHogAiContext),
    ThreadContextElement<PostHogAiContext?> {
    override fun updateThreadContext(context: CoroutineContext): PostHogAiContext? {
        val previous = onThread.get()
        onThread.set(this)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: PostHogAiContext?,
    ) {
        onThread.set(oldState)
    }

    /** Finds the AI trace of the current coroutine or thread. */
    public companion object Key : CoroutineContext.Key<PostHogAiContext> {
        private val onThread = ThreadLocal<PostHogAiContext?>()

        /** The AI trace of the current coroutine, if any. */
        public suspend fun current(): PostHogAiContext? = currentCoroutineContext()[Key]

        /** The AI trace of the coroutine running on this thread, for callbacks that cannot suspend. */
        public fun currentThread(): PostHogAiContext? = onThread.get()
    }
}

/**
 * A message to or from a model, for `$ai_input` and `$ai_output_choices`: a [role] (`system`,
 * `user`, `assistant`, `tool`) and its [content], text or a list of parts (maps such as
 * `mapOf("type" to "text", "text" to "…")`). [properties] adds fields such as `tool_calls` or
 * `tool_call_id`.
 */
public class PostHogAiMessage(
    /** The message's role. */
    public val role: String,
    /** Its content: text or a list of parts. */
    public val content: Any?,
    /** Further fields of the message. */
    public val properties: Map<String, Any?> = emptyMap(),
) {
    internal fun toMap(): Map<String, Any?> = properties + mapOf("role" to role, "content" to content)

    /** Messages by role. */
    public companion object {
        /** A system prompt. */
        public fun system(text: String): PostHogAiMessage = PostHogAiMessage("system", text)

        /** The user's message. */
        public fun user(text: String): PostHogAiMessage = PostHogAiMessage("user", text)

        /** The model's answer. */
        public fun assistant(text: String?): PostHogAiMessage = PostHogAiMessage("assistant", text)
    }
}

/** What the AI events have in common. */
public sealed class PostHogAiEvent {
    /** The trace (`$ai_trace_id`); the surrounding trace's, else a new one. */
    public var traceId: String? = null

    /** The conversation (`$ai_session_id`); the surrounding trace's, if any. */
    public var sessionId: String? = null

    /** This event's own id (`$ai_span_id`). */
    public var spanId: String? = null

    /** Its name (`$ai_span_name`). */
    public var spanName: String? = null

    /** Its parent, a trace or span id (`$ai_parent_id`); the surrounding span's, if any. */
    public var parentId: String? = null

    /** How long it took (`$ai_latency`, sent in seconds). */
    public var latency: Duration? = null

    /** Whether it failed (`$ai_is_error`); set by [error]. */
    public var isError: Boolean? = null

    /** What went wrong (`$ai_error`): a Throwable (its message is sent), text or a map. */
    public var error: Any? = null

    /** Further properties of the event. */
    public val properties: MutableMap<String, Any?> = mutableMapOf()

    internal open fun specific(): Map<String, Any?> = emptyMap()

    internal open val contentKeys: Set<String> = emptySet()

    internal fun toProperties(
        defaultTraceId: String,
        defaultParentId: String?,
        defaultSessionId: String?,
        privacyMode: Boolean,
    ): Map<String, Any?> {
        val failure = error
        val all =
            properties +
                specific() +
                mapOf(
                    "\$ai_trace_id" to (traceId ?: defaultTraceId),
                    "\$ai_session_id" to (sessionId ?: defaultSessionId),
                    "\$ai_span_id" to spanId,
                    "\$ai_span_name" to spanName,
                    "\$ai_parent_id" to (parentId ?: defaultParentId?.takeIf { it != spanId }),
                    "\$ai_latency" to latency?.toDouble(DurationUnit.SECONDS),
                    "\$ai_is_error" to (isError ?: failure?.let { true }),
                    "\$ai_error" to if (failure is Throwable) failure.message ?: failure::class.java.name else failure,
                )
        return if (privacyMode) all - contentKeys else all
    }
}

/** A model call (`$ai_generation`); see [PostHogAi.captureGeneration]. */
public class PostHogAiGeneration internal constructor() : PostHogAiEvent() {
    /** The model (`$ai_model`), e.g. `gpt-5-mini`. */
    public var model: String? = null

    /** The provider (`$ai_provider`), e.g. `openai`, `anthropic`. */
    public var provider: String? = null

    /** The messages sent (`$ai_input`). */
    public var input: List<PostHogAiMessage>? = null

    /** The choices returned (`$ai_output_choices`). */
    public var output: List<PostHogAiMessage>? = null

    /** Input tokens (`$ai_input_tokens`). */
    public var inputTokens: Int? = null

    /** Output tokens (`$ai_output_tokens`). */
    public var outputTokens: Int? = null

    /** Input tokens read from the provider's cache (`$ai_cache_read_input_tokens`). */
    public var cacheReadInputTokens: Int? = null

    /** Input tokens written to the provider's cache (`$ai_cache_creation_input_tokens`). */
    public var cacheCreationInputTokens: Int? = null

    /** Time to the first token of a streamed answer (`$ai_time_to_first_token`, sent in seconds). */
    public var timeToFirstToken: Duration? = null

    /** Whether the answer was streamed (`$ai_stream`). */
    public var stream: Boolean? = null

    /** The temperature asked for (`$ai_temperature`). */
    public var temperature: Double? = null

    /** The output token limit asked for (`$ai_max_tokens`). */
    public var maxTokens: Int? = null

    /** The tools offered to the model (`$ai_tools`). */
    public var tools: List<Any?>? = null

    /** Why the model stopped (`$ai_stop_reason`), e.g. `stop`, `max_tokens`. */
    public var stopReason: String? = null

    /** The provider's HTTP status (`$ai_http_status`). */
    public var httpStatus: Int? = null

    /** The provider's base URL (`$ai_base_url`). */
    public var baseUrl: String? = null

    /** The full request URL (`$ai_request_url`). */
    public var requestUrl: String? = null

    /** Cost of the input in USD (`$ai_input_cost_usd`); PostHog computes costs when not given. */
    public var inputCostUsd: Double? = null

    /** Cost of the output in USD (`$ai_output_cost_usd`). */
    public var outputCostUsd: Double? = null

    /** Total cost in USD (`$ai_total_cost_usd`). */
    public var totalCostUsd: Double? = null

    override val contentKeys: Set<String> = setOf("\$ai_input", "\$ai_output_choices")

    override fun specific(): Map<String, Any?> =
        mapOf(
            "\$ai_model" to model,
            "\$ai_provider" to provider,
            "\$ai_input" to input?.map { it.toMap() },
            "\$ai_output_choices" to output?.map { it.toMap() },
            "\$ai_input_tokens" to inputTokens,
            "\$ai_output_tokens" to outputTokens,
            "\$ai_cache_read_input_tokens" to cacheReadInputTokens,
            "\$ai_cache_creation_input_tokens" to cacheCreationInputTokens,
            "\$ai_time_to_first_token" to timeToFirstToken?.toDouble(DurationUnit.SECONDS),
            "\$ai_stream" to stream,
            "\$ai_temperature" to temperature,
            "\$ai_max_tokens" to maxTokens,
            "\$ai_tools" to tools,
            "\$ai_stop_reason" to stopReason,
            "\$ai_http_status" to httpStatus,
            "\$ai_base_url" to baseUrl,
            "\$ai_request_url" to requestUrl,
            "\$ai_input_cost_usd" to inputCostUsd,
            "\$ai_output_cost_usd" to outputCostUsd,
            "\$ai_total_cost_usd" to totalCostUsd,
        )
}

/** An embedding model call (`$ai_embedding`); see [PostHogAi.captureEmbedding]. */
public class PostHogAiEmbedding internal constructor() : PostHogAiEvent() {
    /** The model (`$ai_model`). */
    public var model: String? = null

    /** The provider (`$ai_provider`). */
    public var provider: String? = null

    /** The text embedded, one text or several (`$ai_input`). */
    public var input: Any? = null

    /** Input tokens (`$ai_input_tokens`). */
    public var inputTokens: Int? = null

    /** The provider's HTTP status (`$ai_http_status`). */
    public var httpStatus: Int? = null

    /** The provider's base URL (`$ai_base_url`). */
    public var baseUrl: String? = null

    /** Total cost in USD (`$ai_total_cost_usd`). */
    public var totalCostUsd: Double? = null

    override val contentKeys: Set<String> = setOf("\$ai_input")

    override fun specific(): Map<String, Any?> =
        mapOf(
            "\$ai_model" to model,
            "\$ai_provider" to provider,
            "\$ai_input" to input,
            "\$ai_input_tokens" to inputTokens,
            "\$ai_http_status" to httpStatus,
            "\$ai_base_url" to baseUrl,
            "\$ai_total_cost_usd" to totalCostUsd,
        )
}

/** A step that is not a model call (`$ai_span`); see [PostHogAi.captureSpan]. */
public class PostHogAiSpan internal constructor() : PostHogAiEvent() {
    /** What went in (`$ai_input_state`). */
    public var inputState: Any? = null

    /** What came out (`$ai_output_state`). */
    public var outputState: Any? = null

    override val contentKeys: Set<String> = setOf("\$ai_input_state", "\$ai_output_state")

    override fun specific(): Map<String, Any?> = mapOf("\$ai_input_state" to inputState, "\$ai_output_state" to outputState)
}

/** A whole interaction (`$ai_trace`); see [PostHogAi.captureTrace]. */
public class PostHogAiTrace internal constructor() : PostHogAiEvent() {
    /** What went in (`$ai_input_state`). */
    public var inputState: Any? = null

    /** What came out (`$ai_output_state`). */
    public var outputState: Any? = null

    override val contentKeys: Set<String> = setOf("\$ai_input_state", "\$ai_output_state")

    override fun specific(): Map<String, Any?> = mapOf("\$ai_input_state" to inputState, "\$ai_output_state" to outputState)
}

@OptIn(ExperimentalUuidApi::class)
internal fun newAiId(): String = Uuid.generateV7().toString()
