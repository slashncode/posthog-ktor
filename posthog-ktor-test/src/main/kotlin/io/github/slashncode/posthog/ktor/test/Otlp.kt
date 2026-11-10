package io.github.slashncode.posthog.ktor.test

/** A span as PostHog received it over OTLP: tracing (`/i/v1/traces`) or AI observability (`/i/v0/ai/otel`). */
public class CapturedSpan internal constructor(
    /** The span's name, e.g. `GET /orders/{id}` or `chat gpt-5-mini`. */
    public val name: String,
    /** The trace id, as 32 hex digits. */
    public val traceId: String,
    /** The span id, as 16 hex digits. */
    public val spanId: String,
    /** The parent span's id, or null for a root span. */
    public val parentSpanId: String?,
    /** The span kind as OTLP numbers it: 1 internal, 2 server, 3 client, 4 producer, 5 consumer. */
    public val kind: Int,
    /** The span's attributes: String, Boolean, Long, Double, or lists and maps of them. */
    public val attributes: Map<String, Any?>,
    /** The attributes of the resource that sent it (`service.name`…). */
    public val resource: Map<String, Any?>,
    /** The status code: 0 unset, 1 ok, 2 error. */
    public val statusCode: Int,
    /** The names of the span's events, e.g. `exception`. */
    public val events: List<String>,
) {
    /** The attribute [key], or null. */
    public fun attribute(key: String): Any? = attributes[key]

    override fun toString(): String = "span '$name'"
}

/** A log record as PostHog Logs received it over OTLP (`/i/v1/logs`). */
public class CapturedLog internal constructor(
    /** The log line's message. */
    public val body: Any?,
    /** The level as text, e.g. `INFO`. */
    public val severityText: String?,
    /** The record's attributes, the MDC's included (`posthogDistinctId`, `sessionId`). */
    public val attributes: Map<String, Any?>,
    /** The attributes of the resource that sent it (`service.name`…). */
    public val resource: Map<String, Any?>,
    /** The trace id of the span it was written in, if any. */
    public val traceId: String?,
) {
    /** The attribute [key], or null. */
    public fun attribute(key: String): Any? = attributes[key]

    override fun toString(): String = "log '$body'"
}

// A reader for the parts of OTLP's protobuf messages the fake keeps: no protobuf library needed.
internal object OtlpDecoder {
    fun spans(bytes: ByteArray): List<CapturedSpan> =
        Reader(bytes).messages(1).flatMap { resourceSpans ->
            val fields = Reader(resourceSpans).fields()
            val resource = fields[1]?.firstOrNull()?.let { resourceAttributes(it as ByteArray) }.orEmpty()
            fields[2].orEmpty().flatMap { scope -> Reader(scope as ByteArray).messages(2).map { span(it, resource) } }
        }

    fun logs(bytes: ByteArray): List<CapturedLog> =
        Reader(bytes).messages(1).flatMap { resourceLogs ->
            val fields = Reader(resourceLogs).fields()
            val resource = fields[1]?.firstOrNull()?.let { resourceAttributes(it as ByteArray) }.orEmpty()
            fields[2].orEmpty().flatMap { scope -> Reader(scope as ByteArray).messages(2).map { log(it, resource) } }
        }

    private fun resourceAttributes(bytes: ByteArray): Map<String, Any?> = attributes(Reader(bytes).fields()[1].orEmpty())

    private fun span(
        bytes: ByteArray,
        resource: Map<String, Any?>,
    ): CapturedSpan {
        val fields = Reader(bytes).fields()
        val status = fields[15]?.firstOrNull()?.let { Reader(it as ByteArray).fields() }
        return CapturedSpan(
            name = fields.string(5).orEmpty(),
            traceId = fields.hex(1).orEmpty(),
            spanId = fields.hex(2).orEmpty(),
            parentSpanId = fields.hex(4)?.takeIf { it.isNotEmpty() },
            kind = (fields[6]?.firstOrNull() as? Long)?.toInt() ?: 0,
            attributes = attributes(fields[9].orEmpty()),
            resource = resource,
            statusCode = (status?.get(3)?.firstOrNull() as? Long)?.toInt() ?: 0,
            events = fields[11].orEmpty().mapNotNull { Reader(it as ByteArray).fields().string(2) },
        )
    }

    private fun log(
        bytes: ByteArray,
        resource: Map<String, Any?>,
    ): CapturedLog {
        val fields = Reader(bytes).fields()
        return CapturedLog(
            body = fields[5]?.firstOrNull()?.let { anyValue(it as ByteArray) },
            severityText = fields.string(3),
            attributes = attributes(fields[6].orEmpty()),
            resource = resource,
            traceId = fields.hex(9)?.takeIf { it.isNotEmpty() },
        )
    }

    private fun attributes(keyValues: List<Any>): Map<String, Any?> =
        keyValues.associate { keyValue ->
            val fields = Reader(keyValue as ByteArray).fields()
            fields.string(1).orEmpty() to fields[2]?.firstOrNull()?.let { anyValue(it as ByteArray) }
        }

    private fun anyValue(bytes: ByteArray): Any? {
        val fields = Reader(bytes).fields()
        return when {
            fields.containsKey(1) -> fields.string(1)
            fields.containsKey(2) -> (fields[2]!!.first() as Long) != 0L
            fields.containsKey(3) -> fields[3]!!.first() as Long
            fields.containsKey(4) -> Double.fromBits(fields[4]!!.first() as Long)
            fields.containsKey(5) -> Reader(fields[5]!!.first() as ByteArray).messages(1).map(::anyValue)
            fields.containsKey(6) -> attributes(Reader(fields[6]!!.first() as ByteArray).fields()[1].orEmpty())
            fields.containsKey(7) -> fields[7]!!.first()
            else -> null
        }
    }

    private fun Map<Int, List<Any>>.string(field: Int): String? = (this[field]?.firstOrNull() as? ByteArray)?.decodeToString()

    private fun Map<Int, List<Any>>.hex(field: Int): String? =
        (this[field]?.firstOrNull() as? ByteArray)?.joinToString("") { "%02x".format(it) }

    // Protobuf's wire format: varints as Long, fixed64 as Long bits, length-delimited as ByteArray.
    private class Reader(
        private val bytes: ByteArray,
    ) {
        private var position = 0

        fun fields(): Map<Int, List<Any>> {
            val fields = mutableMapOf<Int, MutableList<Any>>()
            while (position < bytes.size) {
                val tag = varint()
                val value: Any =
                    when ((tag and 7).toInt()) {
                        0 -> varint()
                        1 -> fixed(8)
                        2 -> {
                            val length = varint().toInt()
                            bytes.copyOfRange(position, position + length).also { position += length }
                        }
                        5 -> fixed(4)
                        else -> error("Unsupported wire type in tag $tag")
                    }
                fields.getOrPut((tag ushr 3).toInt()) { mutableListOf() } += value
            }
            return fields
        }

        fun messages(field: Int): List<ByteArray> = fields()[field].orEmpty().map { it as ByteArray }

        private fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = bytes[position++].toLong()
                result = result or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0L) return result
                shift += 7
            }
        }

        private fun fixed(size: Int): Long {
            var result = 0L
            for (i in 0 until size) result = result or ((bytes[position + i].toLong() and 0xff) shl (8 * i))
            position += size
            return result
        }
    }
}
