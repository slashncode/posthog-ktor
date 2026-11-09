package io.github.slashncode.posthog.ktor

import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream

/**
 * Accepts what the PostHog SDK sends (batches, possibly gzip-compressed) and keeps the events;
 * answers remote flag evaluation from [remoteFlags] and local evaluation from [flagDefinitions].
 */
class FakePostHog : Closeable {
    private val port = ServerSocket(0).use { it.localPort }
    private val received = CopyOnWriteArrayList<JsonObject>()
    private val flagRequests = CopyOnWriteArrayList<JsonObject>()

    /** A flag's value for remote evaluation: `true`/`false` or a variant, and its payload as JSON text. */
    data class Flag(
        val value: Any,
        val payload: String? = null,
    )

    /** What `/flags` answers, for every distinct id. */
    @Volatile var remoteFlags: Map<String, Flag> = emptyMap()

    /** What `/api/feature_flag/local_evaluation/` answers: the definitions for local evaluation. */
    @Volatile var flagDefinitions = """{"flags":[],"group_type_mapping":{},"cohorts":{}}"""

    val url = "http://localhost:$port"

    private val server =
        embeddedServer(Netty, port = port) {
            routing {
                get("/api/feature_flag/local_evaluation{...}") {
                    call.respondText(flagDefinitions, ContentType.Application.Json)
                }
                post("{...}") {
                    val raw = call.receiveChannel().toByteArray()
                    val gzipped = call.request.header("Content-Encoding") == "gzip"
                    val body = if (gzipped) GZIPInputStream(raw.inputStream()).readBytes() else raw
                    val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
                    if (call.request.path().startsWith("/flags")) {
                        flagRequests += json
                        call.respondText(flagsResponse().toString(), ContentType.Application.Json)
                        return@post
                    }
                    json["batch"]?.jsonArray?.forEach { received += it.jsonObject } ?: received.add(json)
                    call.respondText("""{"status":"Ok"}""")
                }
            }
        }.start(wait = false)

    private fun flagsResponse() =
        buildJsonObject {
            putJsonObject("flags") {
                remoteFlags.entries.forEachIndexed { index, (key, flag) ->
                    putJsonObject(key) {
                        put("key", key)
                        put("enabled", flag.value != false)
                        put("variant", (flag.value as? String)?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
                        putJsonObject("reason") {
                            put("code", "condition_match")
                            put("description", "Matched condition set 1")
                            put("condition_index", 0)
                        }
                        putJsonObject("metadata") {
                            put("id", index + 1)
                            put("version", 1)
                            flag.payload?.let { put("payload", it) }
                        }
                    }
                }
            }
            put("errorsWhileComputingFlags", false)
            put("requestId", "request-1")
        }

    /** Captured events (not identifies, flags requests or other calls). */
    fun events(): List<JsonObject> = received.filter { it["event"] != null }

    /** The bodies of remote flag evaluations. */
    fun flagRequests(): List<JsonObject> = flagRequests.toList()

    override fun close() = server.stop(0, 0)
}

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

fun JsonObject.property(key: String): String? = this["properties"]?.jsonObject?.string(key)

fun JsonObject.arrayProperty(key: String): List<String>? =
    (this["properties"]?.jsonObject?.get(key) as? JsonArray)?.map { it.jsonPrimitive.content }
