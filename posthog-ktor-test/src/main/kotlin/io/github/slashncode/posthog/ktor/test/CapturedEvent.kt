package io.github.slashncode.posthog.ktor.test

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

/** An event as PostHog received it from the SDK. */
public class CapturedEvent internal constructor(
    /** The event as sent, for anything the accessors do not cover. */
    public val json: JsonObject,
) {
    /** The event name, e.g. `order_placed`, `$exception`, `$feature_flag_called`. */
    public val event: String = json["event"]?.jsonPrimitive?.content.orEmpty()

    /** The distinct id it was captured for. */
    public val distinctId: String? = json["distinct_id"]?.jsonPrimitive?.content

    /** All properties, the SDK's own (`$lib`, `$feature/…`) included. */
    public val properties: JsonObject = json["properties"]?.jsonObject ?: JsonObject(emptyMap())

    /** The flags the event carries (`$feature/<key>`): their variant, else whether they are on. */
    public val flags: Map<String, Any?>
        get() =
            properties
                .filterKeys { it.startsWith(FLAG_PREFIX) }
                .map { (key, value) -> key.removePrefix(FLAG_PREFIX) to value.toValue() }
                .toMap()

    /**
     * The property's value: a String, Boolean, Long or Double, null if it is missing or null, or the
     * JSON element itself for arrays and objects.
     */
    public fun property(key: String): Any? = properties[key]?.toValue()

    /**
     * Asserts that the property [key] has the value [expected]; numbers are compared by value
     * (`12` matches `12.0`), anything else by equality with [property].
     */
    public fun property(
        key: String,
        expected: Any?,
    ) {
        val actual = property(key)
        val matches =
            if (expected is Number && actual is Number) {
                BigDecimal(expected.toString()).compareTo(BigDecimal(actual.toString())) == 0
            } else {
                expected == actual
            }
        if (!matches) throw AssertionError("Expected property '$key' of $this to be <$expected> but was <$actual>")
    }

    override fun toString(): String = "$event for $distinctId"

    private companion object {
        const val FLAG_PREFIX = "\$feature/"

        fun JsonElement.toValue(): Any? =
            when (this) {
                is JsonNull -> null
                is JsonPrimitive ->
                    when {
                        isString -> content
                        else -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
                    }
                else -> this
            }
    }
}
