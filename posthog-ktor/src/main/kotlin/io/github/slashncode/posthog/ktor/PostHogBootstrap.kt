package io.github.slashncode.posthog.ktor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * What posthog-js needs to show the right variants on its first render, before it has asked
 * PostHog itself: the value of its `bootstrap` option. Made by `call.posthog.bootstrap(…)`; the
 * values are evaluated without an exposure, which posthog-js records when the page reads a flag.
 *
 * ```kotlin
 * val bootstrap = call.posthog.bootstrap(listOf("checkout-button"))
 * call.respondHtml {
 *     head {
 *         script {
 *             unsafe { +"posthog.init('phc_…', { api_host: '/ingest', bootstrap: ${bootstrap.toJson()} })" }
 *         }
 *     }
 * }
 * ```
 */
public class PostHogBootstrap internal constructor(
    /** The distinct id the flags were evaluated for, which posthog-js should go on with. */
    public val distinctId: String?,
    /** Whether [distinctId] is a known user's (identified or resolved), not an anonymous visitor's. */
    public val isIdentified: Boolean,
    /** The flags that are on: their variant, else `true`. Flags that are off are left out, as posthog-js expects. */
    public val featureFlags: Map<String, Any>,
    /** The payloads of the flags in [featureFlags] that have one, as JSON text. */
    public val featureFlagPayloads: Map<String, String>,
) {
    /**
     * posthog-js' `bootstrap` option (`distinctID`, `isIdentifiedID`, `featureFlags`,
     * `featureFlagPayloads`) as JSON that is safe inside an HTML `<script>` element: `<`, `>`, `&`
     * and the JavaScript line separators are escaped.
     */
    public fun toJson(): String = scriptSafe(json(withIdentity = true))

    internal fun json(withIdentity: Boolean): JsonObject =
        buildJsonObject {
            if (withIdentity && distinctId != null) {
                put("distinctID", distinctId)
                put("isIdentifiedID", isIdentified)
            }
            putJsonObject("featureFlags") {
                featureFlags.forEach { (key, value) ->
                    put(key, if (value is String) JsonPrimitive(value) else JsonPrimitive(value == true))
                }
            }
            putJsonObject("featureFlagPayloads") {
                featureFlagPayloads.forEach { (key, payload) -> put(key, parsed(payload)) }
            }
        }

    private companion object {
        // PostHog keeps payloads as JSON; anything else goes to posthog-js as the string it is.
        fun parsed(payload: String): JsonElement =
            try {
                Json.parseToJsonElement(payload)
            } catch (_: IllegalArgumentException) {
                JsonPrimitive(payload)
            }

        fun scriptSafe(json: JsonObject): String =
            json
                .toString()
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026")
                .replace(" ", "\\u2028")
                .replace(" ", "\\u2029")
    }
}
