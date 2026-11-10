package io.github.slashncode.posthog.ktor

import com.posthog.PersonProfiles
import com.posthog.PostHogBeforeSend
import com.posthog.PostHogEvent
import org.slf4j.LoggerFactory

/**
 * The official SDK's `beforeSend` for the client the plugin builds, so that every event it sends
 * (its own `$exception`, `$feature_flag_called` and `$identify` included, and direct SDK calls)
 * goes through the same steps: dropped while opted out, super properties for the keys the event
 * does not have, the application's hooks in order, then person profiles and GeoIP.
 */
internal class EventPipeline(
    private val client: PostHogClient,
    private val hooks: List<(PostHogEvent) -> PostHogEvent?>,
    private val personProfiles: PersonProfiles,
    private val disableGeoip: Boolean,
) : PostHogBeforeSend {
    override fun run(event: PostHogEvent): PostHogEvent? {
        if (client.isOptedOut) return null
        var current =
            event.withProperties { properties ->
                client.superProperties.forEach { (key, value) -> properties.putIfAbsent(key, value) }
            }
        for (hook in hooks) {
            current =
                try {
                    hook(current)
                } catch (e: Exception) {
                    log.warn("A beforeSend hook failed; the event '{}' is dropped", current.event, e)
                    null
                } ?: return null
        }
        return current.withProperties { properties ->
            when {
                personProfiles == PersonProfiles.NEVER -> properties[PROCESS_PERSON_PROFILE] = false
                // These events are about the person: they create or change the profile, as in posthog-js.
                current.event in PERSON_EVENTS -> properties.remove(PROCESS_PERSON_PROFILE)
            }
            if (disableGeoip || IP !in properties) properties[GEOIP_DISABLE] = true
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(EventPipeline::class.java)!!
        const val IP = "\$ip"
        const val GEOIP_DISABLE = "\$geoip_disable"
        val PERSON_EVENTS = setOf("\$identify", "\$set", "\$create_alias", "\$groupidentify")

        fun PostHogEvent.withProperties(change: (MutableMap<String, Any>) -> Unit): PostHogEvent =
            copy(properties = properties.orEmpty().toMutableMap().also(change))
    }
}

/** posthog-js' property for an event that creates or changes no person profile. */
internal const val PROCESS_PERSON_PROFILE = "\$process_person_profile"
