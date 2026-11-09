package io.github.slashncode.posthog.ktor

import com.posthog.server.PostHogFeatureFlagEvaluations
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory

/**
 * The feature flags of one distinct id, evaluated once: a call's flags come from
 * `call.posthog.flags()`, which evaluates them on first use and keeps them for the rest of the call.
 *
 * Reading a flag's value ([isEnabled], [getFlag]) records an exposure (`$feature_flag_called`), as
 * the official SDK does, and marks the flag as read: events captured later in the same call carry
 * the flags it has read (`$feature/<key>`, `$active_feature_flags`). Reading a payload records
 * nothing. Without a distinct id, or while PostHog is off, every flag is off.
 */
public class PostHogFlags internal constructor(
    /** The official SDK's snapshot, for what this class does not wrap; null if nothing was evaluated. */
    public val evaluations: PostHogFeatureFlagEvaluations?,
) {
    /** The distinct id the flags were evaluated for; null if nothing was evaluated. */
    public val distinctId: String? get() = evaluations?.distinctId

    /** The keys of the flags evaluated, on or off; reading them records no exposure. */
    public val keys: List<String> get() = evaluations?.keys.orEmpty()

    /** Whether the flag is on (any variant counts); records an exposure. */
    public fun isEnabled(key: String): Boolean = evaluations?.isEnabled(key) ?: false

    /** The flag's value: its variant, else whether it is on; null if it was not evaluated. Records an exposure. */
    public fun getFlag(key: String): Any? = evaluations?.getFlag(key)

    /** The payload of the flag's current value as JSON text, if it has one; records no exposure. */
    public fun getFlagPayload(key: String): String? = evaluations?.getFlagPayload(key)

    internal fun accessed(): PostHogFeatureFlagEvaluations? = evaluations?.onlyAccessed()?.takeIf { it.keys.isNotEmpty() }

    internal companion object {
        val NONE = PostHogFlags(null)
        val log = LoggerFactory.getLogger(PostHogFlags::class.java)!!
    }
}

/**
 * The payload of the flag's current value decoded with kotlinx.serialization, e.g. a
 * `@Serializable data class Odds(val weights: List<Int>)`; null if there is none or it does not
 * decode (logged as a warning: a payload edited in PostHog should not fail a request). Records no
 * exposure.
 */
public inline fun <reified T> PostHogFlags.getFlagPayloadAs(
    key: String,
    json: Json = Json,
): T? = getFlagPayloadAs(key, serializer<T>(), json)

/** The payload of the flag's current value decoded with [deserializer]; see the reified overload. */
public fun <T> PostHogFlags.getFlagPayloadAs(
    key: String,
    deserializer: DeserializationStrategy<T>,
    json: Json = Json,
): T? {
    val payload = getFlagPayload(key) ?: return null
    return try {
        json.decodeFromString(deserializer, payload)
    } catch (e: IllegalArgumentException) {
        // SerializationException included.
        PostHogFlags.log.warn("The payload of flag '{}' does not decode as {}", key, deserializer.descriptor.serialName, e)
        null
    }
}
