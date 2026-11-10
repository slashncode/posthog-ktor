package io.github.slashncode.posthog.ktor.test

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A fake PostHog for tests: a local HTTP server that the real client (posthog-ktor with the
 * official SDK) sends to, which keeps what it receives and answers flag evaluations from flags the
 * test sets. Nothing leaves the machine.
 *
 * ```kotlin
 * PostHogFake().use { posthog ->
 *     posthog.flag("new-checkout", true)
 *     testApplication {
 *         environment { config = posthog.applicationConfig() }
 *         application { module() }
 *         client.post("/checkout") { header(DISTINCT_ID_HEADER, "anon-1") }
 *         posthog.assertCaptured("order_placed") { property("total", 12) }
 *     }
 * }
 * ```
 */
public class PostHogFake : AutoCloseable {
    private val received = CopyOnWriteArrayList<CapturedEvent>()
    private val flagRequestBodies = CopyOnWriteArrayList<JsonObject>()
    private val flags = ConcurrentHashMap<String, Pair<Any, String?>>()
    private val receivedSpans = CopyOnWriteArrayList<CapturedSpan>()
    private val receivedAiSpans = CopyOnWriteArrayList<CapturedSpan>()
    private val receivedLogs = CopyOnWriteArrayList<CapturedLog>()
    private val earlyAccess = CopyOnWriteArrayList<JsonObject>()
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(4) { task -> Thread(task, "posthog-fake").apply { isDaemon = true } }
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    handle(exchange)
                } finally {
                    exchange.close()
                }
            }
            executor = this@PostHogFake.executor
            start()
        }

    /** The fake's address, for the `host` of the client under test. */
    public val host: String = "http://127.0.0.1:${server.address.port}"

    /** The project token [applicationConfig] configures. Any token works. */
    public val projectToken: String = "phc_fake"

    /**
     * The flag definitions answered for local evaluation (a client with a `secretKey`), in the shape
     * of PostHog's `/api/feature_flag/local_evaluation` response. Without one, local evaluation finds
     * no flags and the client falls back to [flag]'s remote answers.
     */
    @Volatile
    public var flagDefinitions: String = """{"flags":[],"group_type_mapping":{},"cohorts":{}}"""

    /**
     * Sets what remote flag evaluation answers for every distinct id: `true`/`false`, or a variant;
     * [payload] is the payload as JSON text.
     */
    public fun flag(
        key: String,
        value: Any,
        payload: String? = null,
    ) {
        require(value is Boolean || value is String) { "A flag's value is a Boolean or a variant name, not $value" }
        flags[key] = value to payload
    }

    /** Everything captured so far, in the order received: `$identify`, `$exception` and `$feature_flag_called` included. */
    public val events: List<CapturedEvent> get() = received.toList()

    /** The events named [event] captured so far. */
    public fun captured(event: String): List<CapturedEvent> = received.filter { it.event == event }

    /** The bodies of the remote flag evaluations so far (`distinct_id`, `person_properties`, `groups`…). */
    public val flagRequests: List<JsonObject> get() = flagRequestBodies.toList()

    /** The spans received for PostHog's tracing (`/i/v1/traces`). */
    public val spans: List<CapturedSpan> get() = receivedSpans.toList()

    /** The spans received for AI observability (`/i/v0/ai/otel`), which PostHog turns into `$ai_*` events. */
    public val aiSpans: List<CapturedSpan> get() = receivedAiSpans.toList()

    /** The log records received for PostHog Logs (`/i/v1/logs`). */
    public val logs: List<CapturedLog> get() = receivedLogs.toList()

    /**
     * Adds an early access feature to what `/api/early_access_features/` answers, as PostHog lists
     * them for posthog-js; [payload] is JSON text.
     */
    public fun earlyAccessFeature(
        flagKey: String,
        name: String,
        stage: String = "beta",
        description: String? = null,
        documentationUrl: String? = null,
        payload: String? = null,
    ) {
        earlyAccess +=
            buildJsonObject {
                put("flagKey", flagKey)
                put("name", name)
                put("stage", stage)
                put("description", description.orEmpty())
                put("documentationUrl", documentationUrl.orEmpty())
                put("payload", payload?.let(Json::parseToJsonElement) ?: JsonNull)
            }
    }

    /** Forgets what was received; the flags and early access features stay. */
    public fun reset() {
        received.clear()
        flagRequestBodies.clear()
        receivedSpans.clear()
        receivedAiSpans.clear()
        receivedLogs.clear()
    }

    /** Waits up to [timeout] for a tracing span named [name] for which [check] passes, and returns it. */
    public fun assertSpan(
        name: String,
        timeout: Duration = 10.seconds,
        check: CapturedSpan.() -> Unit = {},
    ): CapturedSpan = awaitMatch("span '$name'", timeout, { receivedSpans.filter { it.name == name } }, check)

    /** Waits up to [timeout] for an AI span named [name] for which [check] passes, and returns it. */
    public fun assertAiSpan(
        name: String,
        timeout: Duration = 10.seconds,
        check: CapturedSpan.() -> Unit = {},
    ): CapturedSpan = awaitMatch("AI span '$name'", timeout, { receivedAiSpans.filter { it.name == name } }, check)

    /** Waits up to [timeout] for a log record whose body contains [text] and for which [check] passes, and returns it. */
    public fun assertLog(
        text: String,
        timeout: Duration = 10.seconds,
        check: CapturedLog.() -> Unit = {},
    ): CapturedLog = awaitMatch("log '$text'", timeout, { receivedLogs.filter { it.body.toString().contains(text) } }, check)

    private fun <T> awaitMatch(
        what: String,
        timeout: Duration,
        candidates: () -> List<T>,
        check: T.() -> Unit,
    ): T {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        var lastFailure: AssertionError? = null
        while (true) {
            for (candidate in candidates()) {
                try {
                    candidate.check()
                    return candidate
                } catch (failure: AssertionError) {
                    lastFailure = failure
                }
            }
            if (deadline.hasPassedNow()) break
            Thread.sleep(POLL.inWholeMilliseconds)
        }
        throw AssertionError(
            "Expected $what within $timeout; received spans ${receivedSpans.map {
                it.name
            }}, AI spans ${receivedAiSpans.map { it.name }}, ${receivedLogs.size} logs",
            lastFailure,
        )
    }

    /**
     * Configuration that points the PostHog plugin at this fake ([projectToken], [host], events sent
     * one by one), on top of [base] if given: `environment { config = posthog.applicationConfig() }`.
     */
    public fun applicationConfig(base: ApplicationConfig? = null): ApplicationConfig {
        val config =
            MapApplicationConfig(
                "posthog.projectToken" to projectToken,
                "posthog.host" to host,
                "posthog.flushAt" to "1",
            )
        return base?.mergeWith(config) ?: config
    }

    /**
     * Waits up to [timeout] until an event named [event] has arrived for which [check] passes, and
     * returns it; fails with what did arrive otherwise. The SDK sends from a background thread, so
     * events arrive shortly after they are captured.
     */
    public fun assertCaptured(
        event: String,
        timeout: Duration = 5.seconds,
        check: CapturedEvent.() -> Unit = {},
    ): CapturedEvent {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        var lastFailure: AssertionError? = null
        while (true) {
            for (candidate in captured(event)) {
                try {
                    candidate.check()
                    return candidate
                } catch (failure: AssertionError) {
                    lastFailure = failure
                }
            }
            if (deadline.hasPassedNow()) break
            Thread.sleep(POLL.inWholeMilliseconds)
        }
        val seen = received.joinToString { it.event }.ifEmpty { "nothing" }
        throw AssertionError("Expected '$event' to be captured within $timeout; received: $seen", lastFailure)
    }

    /** Waits [within] and fails if an event named [event] arrived (or had arrived) meanwhile. */
    public fun assertNotCaptured(
        event: String,
        within: Duration = 500.milliseconds,
    ) {
        Thread.sleep(within.inWholeMilliseconds)
        val found = captured(event)
        if (found.isNotEmpty()) throw AssertionError("Expected '$event' not to be captured, but it was: $found")
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        when {
            exchange.requestMethod == "GET" && path.startsWith("/api/feature_flag/local_evaluation") ->
                exchange.respond(200, flagDefinitions)
            exchange.requestMethod == "GET" && path.startsWith("/api/early_access_features") -> {
                val stages =
                    exchange.requestURI.rawQuery
                        .orEmpty()
                        .split(
                            '&',
                        ).filter { it.startsWith("stage=") }
                        .map { it.removePrefix("stage=") }
                val features = earlyAccess.filter { stages.isEmpty() || it["stage"]?.jsonPrimitive?.content in stages }
                exchange.respond(200, buildJsonObject { put("earlyAccessFeatures", JsonArray(features)) }.toString())
            }
            exchange.requestMethod == "POST" && path == "/i/v1/traces" -> {
                receivedSpans += OtlpDecoder.spans(exchange.body())
                exchange.respondOtlp()
            }
            exchange.requestMethod == "POST" && path == "/i/v0/ai/otel" -> {
                receivedAiSpans += OtlpDecoder.spans(exchange.body())
                exchange.respondOtlp()
            }
            exchange.requestMethod == "POST" && path == "/i/v1/logs" -> {
                receivedLogs += OtlpDecoder.logs(exchange.body())
                exchange.respondOtlp()
            }
            exchange.requestMethod == "POST" && path.startsWith("/flags") -> {
                flagRequestBodies += exchange.jsonBody()
                exchange.respond(200, flagsResponse().toString())
            }
            exchange.requestMethod == "POST" -> {
                val body = exchange.jsonBody()
                val batch = body["batch"]?.jsonArray?.map { it.jsonObject } ?: listOf(body)
                batch.filter { it["event"] != null }.forEach { received += CapturedEvent(it) }
                exchange.respond(200, """{"status":"Ok"}""")
            }
            else -> exchange.respond(404, "{}")
        }
    }

    private fun HttpExchange.body(): ByteArray {
        val raw = requestBody.readBytes()
        val gzipped = requestHeaders.getFirst("Content-Encoding") == "gzip"
        return if (gzipped) GZIPInputStream(raw.inputStream()).readBytes() else raw
    }

    private fun HttpExchange.jsonBody(): JsonObject = Json.parseToJsonElement(body().decodeToString()).jsonObject

    // An empty Export*ServiceResponse: everything accepted.
    private fun HttpExchange.respondOtlp() {
        responseHeaders.add("Content-Type", "application/x-protobuf")
        sendResponseHeaders(200, -1)
    }

    private fun HttpExchange.respond(
        status: Int,
        json: String,
    ) {
        val bytes = json.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    // PostHog's /flags (v2) answer for every distinct id alike.
    private fun flagsResponse() =
        buildJsonObject {
            putJsonObject("flags") {
                flags.entries.sortedBy { it.key }.forEachIndexed { index, (key, flag) ->
                    val (value, payload) = flag
                    putJsonObject(key) {
                        put("key", key)
                        put("enabled", value != false)
                        put("variant", (value as? String)?.let(::JsonPrimitive) ?: JsonNull)
                        putJsonObject("reason") {
                            put("code", "condition_match")
                            put("description", "Set by the test")
                            put("condition_index", 0)
                        }
                        putJsonObject("metadata") {
                            put("id", index + 1)
                            put("version", 1)
                            payload?.let { put("payload", it) }
                        }
                    }
                }
            }
            put("errorsWhileComputingFlags", false)
            put("requestId", "posthog-fake")
        }

    private companion object {
        val POLL = 20.milliseconds
    }
}
