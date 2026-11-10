package io.github.slashncode.posthog.ktor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** An early access feature people can opt into, as posthog-js' `getEarlyAccessFeatures` lists them. */
public class PostHogEarlyAccessFeature internal constructor(
    /** The key of the feature flag it releases. */
    public val flagKey: String,
    /** Its name. */
    public val name: String,
    /** Its description, if any. */
    public val description: String?,
    /** Its stage: `concept`, `alpha`, `beta` or `general-availability`. */
    public val stage: String,
    /** A link to its documentation, if any. */
    public val documentationUrl: String?,
    /** Its payload as JSON text, if any. */
    public val payload: String?,
) {
    override fun toString(): String = "PostHogEarlyAccessFeature(flagKey=$flagKey, name=$name, stage=$stage)"
}

/**
 * The project's early access features, of the given [stages] or all, for an opt-in page rendered
 * by the server. Kept for a minute; empty while PostHog is off or if PostHog cannot be reached.
 */
public suspend fun PostHogClient.getEarlyAccessFeatures(stages: Collection<String> = emptyList()): List<PostHogEarlyAccessFeature> {
    val token = projectToken ?: return emptyList()
    val key = stages.sorted().joinToString(",")
    earlyAccessCache[key]?.takeIf { it.fresh }?.let { return it.features }
    val query =
        buildString {
            append("token=").append(URLEncoder.encode(token, Charsets.UTF_8))
            stages.forEach { append("&stage=").append(URLEncoder.encode(it, Charsets.UTF_8)) }
        }
    return try {
        val features = withContext(Dispatchers.IO) { fetchEarlyAccessFeatures("${host.trimEnd('/')}/api/early_access_features/?$query") }
        earlyAccessCache[key] = CachedFeatures(features, System.nanoTime())
        features
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        earlyAccessLog.warn("Could not load the early access features", e)
        emptyList()
    }
}

/**
 * Opts [distinctId], or the current context's person, into the early access feature of [flagKey]
 * (or out of it), as posthog-js' `updateEarlyAccessFeatureEnrollment`: a `$feature_enrollment_update`
 * event and the person property `$feature_enrollment/<flagKey>`, which the flag's enrollment
 * condition tests.
 */
public fun PostHogClient.updateEarlyAccessFeatureEnrollment(
    flagKey: String,
    isEnrolled: Boolean,
    stage: String? = null,
    distinctId: String? = null,
) {
    capture(ENROLLMENT_UPDATE, enrollmentProperties(flagKey, isEnrolled, stage), distinctId)
}

/**
 * Opts the call's person into the early access feature of [flagKey], or out of it; the call's
 * flags are evaluated again with the new enrollment. See [PostHogClient.updateEarlyAccessFeatureEnrollment].
 */
public fun PostHogCall.updateEarlyAccessFeatureEnrollment(
    flagKey: String,
    isEnrolled: Boolean,
    stage: String? = null,
) {
    val id = distinctId ?: return
    state?.processPersons()
    setPersonPropertiesForFlags(mapOf("$ENROLLMENT/$flagKey" to isEnrolled))
    client.capture(ENROLLMENT_UPDATE, enrollmentProperties(flagKey, isEnrolled, stage), id, personContext()) { null }
}

private const val ENROLLMENT_UPDATE = "\$feature_enrollment_update"
private const val ENROLLMENT = "\$feature_enrollment"
private val CACHE_FOR = Duration.ofMinutes(1)

private fun enrollmentProperties(
    flagKey: String,
    isEnrolled: Boolean,
    stage: String?,
): Map<String, Any?> =
    mapOf(
        "\$feature_flag" to flagKey,
        ENROLLMENT to isEnrolled,
        "\$feature_enrollment_stage" to stage,
        "\$set" to mapOf("$ENROLLMENT/$flagKey" to isEnrolled),
    )

internal class CachedFeatures(
    val features: List<PostHogEarlyAccessFeature>,
    val loadedAt: Long,
) {
    val fresh: Boolean get() = System.nanoTime() - loadedAt < CACHE_FOR.toNanos()
}

private val earlyAccessLog = LoggerFactory.getLogger("io.github.slashncode.posthog.ktor.EarlyAccess")
private val earlyAccessHttp: HttpClient by lazy {
    HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
}

private fun fetchEarlyAccessFeatures(url: String): List<PostHogEarlyAccessFeature> {
    val request =
        HttpRequest
            .newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .GET()
            .build()
    val response = earlyAccessHttp.send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) { "PostHog answered ${response.statusCode()}" }
    val features = Json.parseToJsonElement(response.body()).jsonObject["earlyAccessFeatures"] as? JsonArray ?: return emptyList()
    return features.mapNotNull { element ->
        val feature = element as? JsonObject ?: return@mapNotNull null
        val flagKey = feature.text("flagKey") ?: return@mapNotNull null
        PostHogEarlyAccessFeature(
            flagKey = flagKey,
            name = feature.text("name") ?: flagKey,
            description = feature.text("description")?.takeIf { it.isNotEmpty() },
            stage = feature.text("stage") ?: "beta",
            documentationUrl = feature.text("documentationUrl")?.takeIf { it.isNotEmpty() },
            payload = feature["payload"]?.takeIf { it !is JsonPrimitive || it.contentOrNull != null }?.toString(),
        )
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
