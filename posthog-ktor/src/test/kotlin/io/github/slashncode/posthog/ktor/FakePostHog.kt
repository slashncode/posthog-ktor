package io.github.slashncode.posthog.ktor

import io.github.slashncode.posthog.ktor.test.PostHogFake
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The library's own tests run against posthog-ktor-test's PostHogFake, reading the raw JSON.

/** A flag's remote answer: `true`/`false` or a variant, and its payload as JSON text. */
data class Flag(
    val value: Any,
    val payload: String? = null,
)

/** Sets the remote answers of these flags. */
var PostHogFake.remoteFlags: Map<String, Flag>
    get() = error("write-only")
    set(value) = value.forEach { (key, flag) -> flag(key, flag.value, flag.payload) }

/** Every event received, as sent. */
fun PostHogFake.sent(): List<JsonObject> = events.map { it.json }

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

fun JsonObject.property(key: String): String? = this["properties"]?.jsonObject?.string(key)

fun JsonObject.arrayProperty(key: String): List<String>? =
    (this["properties"]?.jsonObject?.get(key) as? JsonArray)?.map { it.jsonPrimitive.content }
