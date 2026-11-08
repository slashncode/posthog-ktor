package io.github.slashncode.posthog.ktor

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream

/** Accepts what the PostHog SDK sends (batches, possibly gzip-compressed) and keeps the events. */
class FakePostHog : Closeable {
    private val port = ServerSocket(0).use { it.localPort }
    private val received = CopyOnWriteArrayList<JsonObject>()

    val url = "http://localhost:$port"

    private val server =
        embeddedServer(Netty, port = port) {
            routing {
                post("{...}") {
                    val raw = call.receiveChannel().toByteArray()
                    val gzipped = call.request.header("Content-Encoding") == "gzip"
                    val body = if (gzipped) GZIPInputStream(raw.inputStream()).readBytes() else raw
                    val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
                    json["batch"]?.jsonArray?.forEach { received += it.jsonObject } ?: received.add(json)
                    call.respondText("""{"status":"Ok"}""")
                }
            }
        }.start(wait = false)

    /** Captured events (not identifies, flags requests or other calls). */
    fun events(): List<JsonObject> = received.filter { it["event"] != null }

    override fun close() = server.stop(0, 0)
}

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

fun JsonObject.property(key: String): String? = this["properties"]?.jsonObject?.string(key)
