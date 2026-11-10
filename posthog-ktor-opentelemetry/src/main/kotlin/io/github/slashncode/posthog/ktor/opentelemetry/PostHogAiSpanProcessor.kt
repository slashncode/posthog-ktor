package io.github.slashncode.posthog.ktor.opentelemetry

import io.github.slashncode.posthog.ktor.PostHogClient
import io.github.slashncode.posthog.ktor.PostHogContext
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.context.Context
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.DelegatingSpanData
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends the AI spans of OpenTelemetry instrumentation (`gen_ai.*`, `llm.*`, `ai.*`, `traceloop.*`:
 * Koog, Spring AI, OpenLLMetry) to PostHog's AI observability, which turns them into `$ai_generation`,
 * `$ai_embedding`, `$ai_span` and `$ai_trace` events; other spans are left out. Like posthog-node's
 * `PostHogSpanProcessor`: each AI span gets the person (`posthog.distinct_id`), the session
 * (`$session_id`) and the properties of the [PostHogContext] it started in, unless the span names a
 * person itself, and with [privacyMode] prompts, outputs, tool arguments and error messages stay
 * behind. Only this processor's export sees the added attributes.
 *
 * The [PostHogOpenTelemetry] plugin registers one; add one yourself to a library that builds its
 * own OpenTelemetry SDK: `addSpanProcessor(application.posthog.aiSpanProcessor())`.
 */
public class PostHogAiSpanProcessor(
    projectToken: String,
    host: String,
    private val privacyMode: Boolean = false,
    flushInterval: Duration = Duration.ofSeconds(5),
) : SpanProcessor {
    private val contexts = ConcurrentHashMap<String, CapturedContext>()
    private val exporter =
        ContextExporter(
            OtlpHttpSpanExporter
                .builder()
                .setEndpoint("${host.trimEnd('/')}/i/v0/ai/otel")
                .addHeader("Authorization", "Bearer $projectToken")
                .build(),
        )
    private val inner = BatchSpanProcessor.builder(exporter).setScheduleDelay(flushInterval).build()

    override fun onStart(
        parentContext: Context,
        span: ReadWriteSpan,
    ) {
        PostHogContext.currentThread()?.let { context ->
            contexts[span.spanContext.spanId] = CapturedContext(context.distinctId, context.sessionId, context.properties)
        }
        inner.onStart(parentContext, span)
    }

    override fun isStartRequired(): Boolean = true

    override fun onEnd(span: ReadableSpan) {
        val spanId = span.spanContext.spanId
        if (isAiSpan(span.name, span.attributes)) {
            // Read back when the batch is exported; a span the batch drops leaves its entry behind, bounded below.
            contexts[spanId]?.let { exporter.pending[spanId] = it }
            if (exporter.pending.size > MAX_PENDING) exporter.pending.clear()
            inner.onEnd(span)
        }
        contexts.remove(spanId)
    }

    override fun isEndRequired(): Boolean = true

    override fun shutdown(): CompletableResultCode = inner.shutdown()

    override fun forceFlush(): CompletableResultCode = inner.forceFlush()

    private class CapturedContext(
        val distinctId: String?,
        val sessionId: String?,
        val properties: Map<String, Any>,
    )

    private inner class ContextExporter(
        private val delegate: SpanExporter,
    ) : SpanExporter {
        val pending = ConcurrentHashMap<String, CapturedContext>()

        override fun export(spans: Collection<SpanData>): CompletableResultCode =
            delegate.export(spans.map { span -> shareable(span, pending.remove(span.spanId)) })

        override fun flush(): CompletableResultCode = delegate.flush()

        override fun shutdown(): CompletableResultCode = delegate.shutdown()
    }

    private fun shareable(
        span: SpanData,
        context: CapturedContext?,
    ): SpanData = shareable(span, context?.distinctId, context?.sessionId, context?.properties.orEmpty(), privacyMode)

    internal companion object {
        private const val DISTINCT_ID = "posthog.distinct_id"
        private const val SESSION_ID = "\$session_id"
        private const val MAX_PENDING = 10_000
        private val AI_PREFIXES = listOf("gen_ai.", "llm.", "ai.", "traceloop.")
        private val IDENTITY_ATTRIBUTES = listOf(DISTINCT_ID, "user.id")

        fun shareable(
            span: SpanData,
            distinctId: String?,
            sessionId: String?,
            properties: Map<String, Any>,
            privacyMode: Boolean,
        ): SpanData {
            val attributes = Attributes.builder()
            // Attributes already on the span win over the context's properties, which win over its ids.
            val named = IDENTITY_ATTRIBUTES.any { span.attributes.get(AttributeKey.stringKey(it)) != null }
            if (!named) distinctId?.let { attributes.put(DISTINCT_ID, it) }
            sessionId?.let { attributes.put(SESSION_ID, it) }
            properties.forEach { (key, value) -> attributes.putValue(key, value) }
            span.attributes.forEach { key, value ->
                @Suppress("UNCHECKED_CAST")
                if (!privacyMode || !isContent(key.key)) attributes.put(key as AttributeKey<Any>, value)
            }
            val events = if (privacyMode) span.events.filter { it.name == "exception" }.map(::withoutContent) else span.events
            val merged = attributes.build()
            return object : DelegatingSpanData(span) {
                override fun getAttributes(): Attributes = merged

                override fun getTotalAttributeCount(): Int = merged.size()

                override fun getEvents(): List<EventData> = events

                override fun getTotalRecordedEvents(): Int = events.size
            }
        }

        private fun withoutContent(event: EventData): EventData {
            val attributes = Attributes.builder()
            event.attributes.forEach { key, value ->
                @Suppress("UNCHECKED_CAST")
                if (!isContent(key.key)) attributes.put(key as AttributeKey<Any>, value)
            }
            return EventData.create(event.epochNanos, event.name, attributes.build())
        }

        // What posthog-js' processor strips in privacy mode: prompts, outputs, tool arguments, errors.
        private val CONTENT_ATTRIBUTES =
            setOf(
                "gen_ai.input.messages",
                "gen_ai.output.messages",
                "gen_ai.system_instructions",
                "gen_ai.prompt",
                "gen_ai.completion",
                "gen_ai.tool.call.arguments",
                "gen_ai.tool.call.result",
                "gen_ai.retrieval.documents",
                "gen_ai.retrieval.query.text",
                "gen_ai.memory.query.text",
                "gen_ai.memory.records",
                "gen_ai.evaluation.explanation",
                "gen_ai.task.input",
                "gen_ai.task.output",
                "gen_ai.guardrail.input",
                "gen_ai.guardrail.output",
                "gen_ai.guardrail.error.message",
                "gen_ai.response.revised_prompt",
                "ai.prompt",
                "ai.prompt.messages",
                "ai.response.text",
                "ai.response.object",
                "ai.response.toolCalls",
                "ai.response.reasoning",
                "ai.toolCall.args",
                "ai.toolCall.result",
                "ai.value",
                "ai.values",
                "ai.embedding",
                "ai.embeddings",
                "ai.documents",
                "traceloop.entity.input",
                "traceloop.entity.output",
                "traceloop.prompt.template",
                "input.value",
                "output.value",
                "llm.function_call",
                "llm.prompt_template.template",
                "llm.prompt_template.variables",
                "llm.invocation_parameters",
                "exception.message",
                "exception.stacktrace",
                "error.message",
            )
        private val CONTENT_PREFIXES =
            listOf(
                "gen_ai.prompt.",
                "gen_ai.completion.",
                "llm.prompts.",
                "llm.completions.",
                "llm.input_messages.",
                "llm.output_messages.",
                "llm.choices.",
                "traceloop.prompt.template_variables.",
            )
        private val NOT_CONTENT = setOf("gen_ai.prompt.name", "gen_ai.prompt.version")

        fun isAiSpan(
            name: String,
            attributes: Attributes,
        ): Boolean =
            AI_PREFIXES.any { name.startsWith(it) } || attributes.asMap().keys.any { key -> AI_PREFIXES.any { key.key.startsWith(it) } }

        fun isContent(key: String): Boolean =
            key !in NOT_CONTENT && (key in CONTENT_ATTRIBUTES || CONTENT_PREFIXES.any { key.startsWith(it) })

        // Plain values stay what they are; anything else goes as JSON text, as posthog-js sends it.
        private fun io.opentelemetry.api.common.AttributesBuilder.putValue(
            key: String,
            value: Any,
        ) {
            when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Int -> put(key, value.toLong())
                is Long -> put(key, value)
                is Number -> put(key, value.toDouble())
                else -> put(key, Json.encodeToString(JsonElement.serializer(), toJson(value)))
            }
        }

        private fun toJson(value: Any?): JsonElement =
            when (value) {
                null -> JsonNull
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Map<*, *> -> JsonObject(value.entries.associate { (key, inner) -> key.toString() to toJson(inner) })
                is Iterable<*> -> JsonArray(value.map(::toJson))
                else -> JsonPrimitive(value.toString())
            }
    }
}

/**
 * A [PostHogAiSpanProcessor] for this client's project, for an OpenTelemetry SDK built elsewhere (a
 * model library's own); null while PostHog is off. [privacyMode] defaults to the plugin's.
 */
public fun PostHogClient.aiSpanProcessor(
    privacyMode: Boolean? = null,
    flushInterval: Duration = Duration.ofSeconds(5),
): PostHogAiSpanProcessor? {
    val token = projectToken ?: return null
    return PostHogAiSpanProcessor(token, host, privacyMode ?: this.privacyMode, flushInterval)
}

/**
 * The exporting half of [PostHogAiSpanProcessor], for libraries that take a `SpanExporter` rather than
 * a span processor, such as Koog's OpenTelemetry feature: AI spans only, to PostHog's AI observability,
 * with [privacyMode]. An exporter runs after the request is gone, so it cannot see the
 * [PostHogContext]: name the person on the spans or their resource
 * (`posthog.distinct_id`, e.g. Koog's `addResourceAttributes`), or events are anonymous.
 */
public class PostHogAiSpanExporter(
    projectToken: String,
    host: String,
    private val privacyMode: Boolean = false,
) : SpanExporter {
    private val delegate =
        OtlpHttpSpanExporter
            .builder()
            .setEndpoint("${host.trimEnd('/')}/i/v0/ai/otel")
            .addHeader("Authorization", "Bearer $projectToken")
            .build()

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        val shared =
            spans
                .filter { PostHogAiSpanProcessor.isAiSpan(it.name, it.attributes) }
                .map { PostHogAiSpanProcessor.shareable(it, null, null, emptyMap(), privacyMode) }
        return if (shared.isEmpty()) CompletableResultCode.ofSuccess() else delegate.export(shared)
    }

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()
}

/** A [PostHogAiSpanExporter] for this client's project; null while PostHog is off. [privacyMode] defaults to the plugin's. */
public fun PostHogClient.aiSpanExporter(privacyMode: Boolean? = null): PostHogAiSpanExporter? {
    val token = projectToken ?: return null
    return PostHogAiSpanExporter(token, host, privacyMode ?: this.privacyMode)
}
