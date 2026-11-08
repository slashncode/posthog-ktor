package io.github.slashncode.posthog.ktor

import com.posthog.server.PostHogInterface
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import com.posthog.server.PostHog as PostHogSdk
import com.posthog.server.PostHogConfig as PostHogSdkConfig

/** PostHog's US cloud, the official SDKs' default. The EU cloud is `https://eu.i.posthog.com`. */
public const val POSTHOG_US_HOST: String = "https://us.i.posthog.com"

/**
 * Configuration of the [PostHog] plugin. Values come from the `posthog` section of the application
 * config (`application.conf` or `application.yaml`) first; the `install(PostHog) { }` block can
 * override them.
 *
 * ```hocon
 * posthog {
 *     projectToken = ${?POSTHOG_PROJECT_TOKEN}
 *     host = "https://eu.i.posthog.com"
 * }
 * ```
 */
public class PostHogPluginConfig internal constructor(
    config: ApplicationConfig,
) {
    /**
     * The project token (`phc_…`), public by design. Without one, PostHog stays off: nothing is
     * sent and flags are never on, which suits local development and tests.
     */
    public var projectToken: String? = config.propertyOrNull("projectToken")?.getString()?.takeIf { it.isNotBlank() }

    /** The ingestion host of the project's region. */
    public var host: String = config.propertyOrNull("host")?.getString() ?: POSTHOG_US_HOST

    /**
     * A key that may read the project's flag definitions: the Feature Flags Secure API Key (`phs_…`)
     * or a personal API key with `feature_flag:read`. With it, flags are evaluated locally,
     * without a request to PostHog per evaluation.
     */
    public var secretKey: String? = config.propertyOrNull("secretKey")?.getString()?.takeIf { it.isNotBlank() }

    /**
     * A client created elsewhere, used instead of one built from this configuration. The plugin
     * does not close it; whoever created it does.
     */
    public var client: PostHogInterface? = null

    internal var sdk: PostHogSdkConfig.Builder.() -> Unit = {}

    /** Every other option of the official SDK (batching, proxy, flag polling…), applied last. */
    public fun sdk(configure: PostHogSdkConfig.Builder.() -> Unit) {
        sdk = configure
    }
}

private val clientKey = AttributeKey<PostHogClient>("PostHogClient")

/**
 * The PostHog plugin for Ktor servers. It owns a client of PostHog's official JVM SDK
 * (`com.posthog:posthog-server`) for the lifetime of the application and closes it, flushing
 * what is queued, when the application stops.
 *
 * ```kotlin
 * install(PostHog) {
 *     projectToken = "phc_…"
 *     host = "https://eu.i.posthog.com"
 * }
 * ```
 */
public val PostHog: ApplicationPlugin<PostHogPluginConfig> =
    createApplicationPlugin("PostHog", "posthog", ::PostHogPluginConfig) {
        val owned = pluginConfig.client == null
        val sdk = pluginConfig.client ?: pluginConfig.buildClient()
        application.attributes.put(clientKey, PostHogClient(sdk))
        if (owned && sdk != null) {
            application.monitor.subscribe(ApplicationStopped) { sdk.close() }
        }
    }

private fun PostHogPluginConfig.buildClient(): PostHogInterface? {
    val token = projectToken ?: return null
    val builder = PostHogSdkConfig.builder(token).host(host)
    secretKey?.let { builder.personalApiKey(it).localEvaluation(true) }
    builder.sdk()
    return PostHogSdk.with(builder.build())
}

/** The PostHog client of this application; installing the [PostHog] plugin creates it. */
public val Application.posthog: PostHogClient
    get() =
        attributes.getOrNull(clientKey)
            ?: error("PostHog is not installed: add install(PostHog) to the application module")
