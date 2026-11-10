package io.github.slashncode.posthog.ktor

import com.posthog.PersonProfiles
import com.posthog.PostHogEvent
import com.posthog.server.PostHogEvaluateFlagsOptions
import com.posthog.server.PostHogInterface
import com.posthog.server.PostHogRequestContext
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.Hook
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass
import com.posthog.server.PostHog as PostHogSdk
import com.posthog.server.PostHogConfig as PostHogSdkConfig

/** PostHog's US cloud, the official SDKs' default. */
public const val POSTHOG_US_HOST: String = "https://us.i.posthog.com"

/** PostHog's EU cloud. */
public const val POSTHOG_EU_HOST: String = "https://eu.i.posthog.com"

/**
 * Options of a [PostHogClient]: where events go, how they are batched, what every event carries.
 * `install(PostHog) { }` takes them (see [PostHogPluginConfig]), and so does `PostHogClient { }` for
 * a client made outside the application. Values come from the `posthog` section of the application
 * config (`application.conf` or `application.yaml`) first; code can override them.
 *
 * ```hocon
 * posthog {
 *     projectToken = ${?POSTHOG_PROJECT_TOKEN}
 *     host = "https://eu.i.posthog.com"
 * }
 * ```
 */
public open class PostHogClientConfig internal constructor(
    config: ApplicationConfig,
) {
    /**
     * The project token (`phc_…`), public by design. Without one, PostHog stays off: nothing is
     * sent and flags are never on, which suits local development and tests.
     */
    public var projectToken: String? = config.string("projectToken")

    /** The ingestion host of the project's region. */
    public var host: String = config.string("host") ?: POSTHOG_US_HOST

    /**
     * PostHog's app, for links such as session replay URLs, as posthog-js' `ui_host`. For PostHog's
     * clouds it follows from [host] (`https://eu.i.posthog.com` → `https://eu.posthog.com`); set it
     * for a self-hosted PostHog whose app lives elsewhere.
     */
    public var uiHost: String? = config.string("uiHost")

    /**
     * How long stopping the application waits for queued events to be sent, in milliseconds, as
     * posthog-node's `shutdown(timeout)`. Past it the application stops anyway; an unreachable
     * PostHog never holds up a deployment.
     */
    public var shutdownTimeoutMs: Long = config.long("shutdownTimeoutMs") ?: 30_000

    /**
     * AI observability without content, as posthog-node's `privacyMode`: AI events carry no
     * prompts, outputs or input and output state; models, tokens, latency and costs still go.
     */
    public var privacyMode: Boolean = config.boolean("privacyMode") ?: false

    /**
     * A key that may read the project's flag definitions: the Feature Flags Secure API Key (`phs_…`)
     * or a personal API key with `feature_flag:read`. With it, flags are evaluated locally,
     * without a request to PostHog per evaluation.
     */
    public var secretKey: String? = config.string("secretKey")

    /** How many events are queued before they are sent; the SDK's default if null. Tests use 1. */
    public var flushAt: Int? = config.int("flushAt")

    /** How often queued events are sent at the latest, in seconds; the SDK's default if null. */
    public var flushIntervalSeconds: Int? = config.int("flushIntervalSeconds")

    /** How many events are queued at most; the oldest are dropped beyond it. The SDK's default (10,000) if null. */
    public var maxQueueSize: Int? = config.int("maxQueueSize")

    /** How many events are sent in one request at most; the SDK's default (100) if null. */
    public var maxBatchSize: Int? = config.int("maxBatchSize")

    /** The official SDK's verbose logging: what is sent, how flags are evaluated. */
    public var debug: Boolean = config.boolean("debug") ?: false

    /**
     * Whether PostHog starts opted out: nothing is captured and every flag is off until
     * `application.posthog.optIn()`.
     */
    public var optOut: Boolean = config.boolean("optOut") ?: false

    /**
     * Which events create and update person profiles, as posthog-js' `person_profiles`:
     * [PersonProfiles.ALWAYS] (the default, as the official server SDKs do), [PersonProfiles.IDENTIFIED_ONLY]
     * (events of a call only for a known user: `resolveDistinctId`, `identify`, an explicit distinct
     * id, or after `setPersonProperties`, `group` or `alias` in the call; a browser's anonymous id
     * from the tracing header gets none) or [PersonProfiles.NEVER]. Events without a person profile
     * carry `$process_person_profile: false`. In the config: `personProfiles = identified_only`.
     */
    public var personProfiles: PersonProfiles = config.personProfiles("personProfiles") ?: PersonProfiles.ALWAYS

    /**
     * Whether PostHog skips GeoIP for every event (`$geoip_disable`). Events that carry no `$ip`
     * (captured outside a call, or with [captureIp] off) skip it anyway: PostHog would otherwise
     * locate the person at the server's address. Flags are evaluated without GeoIP too; give
     * location properties to `flagContext` instead.
     */
    public var disableGeoip: Boolean = config.boolean("disableGeoip") ?: false

    /** Whether reading a flag's value records an exposure (`$feature_flag_called`). */
    public var sendFeatureFlagEvent: Boolean = config.boolean("sendFeatureFlagEvent") ?: true

    /**
     * Whether a [secretKey] turns on local evaluation (polling the flag definitions). Off, flags are
     * evaluated by PostHog even with a key.
     */
    public var localEvaluation: Boolean = config.boolean("localEvaluation") ?: true

    /** How often the flag definitions are reloaded for local evaluation, in seconds; the SDK's default (30) if null. */
    public var pollIntervalSeconds: Int? = config.int("pollIntervalSeconds")

    /**
     * Evaluation context tags: only flags tagged with one of them are evaluated (an environment,
     * an app). In the config a list or comma-separated.
     */
    public val evaluationContexts: MutableList<String> = config.list("evaluationContexts").toMutableList()

    /** Package prefixes of in-app stack frames in error tracking, e.g. `com.example`. */
    public val inAppIncludes: MutableList<String> = config.list("inAppIncludes").toMutableList()

    /** Whether exceptions that end a thread (outside calls) are reported too. */
    public var captureUncaughtExceptions: Boolean = config.boolean("captureUncaughtExceptions") ?: false

    /** The release (version) exceptions are reported with, for error tracking. */
    public var releaseIdentifier: String? = config.string("releaseIdentifier")

    /**
     * Whether an event without any distinct id is still sent, under a random id and without a person
     * profile (`$process_person_profile: false`), as the official SDK does. Off, such events are
     * dropped: with posthog-js sending its ids only after the visitor agreed to analytics, that
     * captures nothing about visitors who did not.
     */
    public var captureWithoutDistinctId: Boolean = config.boolean("captureWithoutDistinctId") ?: true

    /**
     * A `posthog-server` client created elsewhere, used instead of one built from these options; the
     * options that configure the official SDK (host, keys, batching…) are then ignored. Closing the
     * [PostHogClient] flushes it but does not close it; whoever created it does. The options that
     * shape every event the SDK sends ([beforeSend], [disableGeoip], [PersonProfiles.NEVER], super
     * properties, opting out) need a client built here; with this one they apply to what
     * posthog-ktor captures only.
     */
    public var sdkClient: PostHogInterface? = null

    internal var sdk: PostHogSdkConfig.Builder.() -> Unit = {}
    internal val beforeSendHooks = mutableListOf<(PostHogEvent) -> PostHogEvent?>()

    /**
     * Changes or drops every event before it is sent, posthog-js' `before_send`: return the event
     * (changed or not), or null to drop it. Hooks run in the order added, each with the previous
     * one's result; a hook that throws drops the event. They see every event the SDK sends
     * (`$exception`, `$feature_flag_called`, `$identify` included), with the context's and the
     * super properties merged in.
     *
     * ```kotlin
     * beforeSend { event -> event.apply { properties?.remove("email") } }
     * beforeSend(sampleByEvent(setOf("search_performed"), 0.1))
     * ```
     */
    public fun beforeSend(hook: (PostHogEvent) -> PostHogEvent?) {
        beforeSendHooks += hook
    }

    /** Every other option of the official SDK (batching, proxy, flag polling…), applied last. */
    public fun sdk(configure: PostHogSdkConfig.Builder.() -> Unit) {
        sdk = configure
    }
}

/**
 * Configuration of the [PostHog] plugin: the options of the client it builds ([PostHogClientConfig])
 * and how it treats calls. Values come from the `posthog` section of the application config first;
 * the `install(PostHog) { }` block can override them.
 */
public class PostHogPluginConfig internal constructor(
    config: ApplicationConfig,
) : PostHogClientConfig(config) {
    /**
     * A client made with `PostHogClient { }` before the application, to share it with work that
     * runs outside it (a dependency injection container, a message consumer, a scheduled task in
     * another process mode). Calls then capture with it, and `application.posthog` is it; the client
     * options set here are ignored. The plugin does not close it: whoever made it does.
     */
    public var client: PostHogClient? = null

    /**
     * Calls whose `User-Agent` contains one of these (ignoring case) capture nothing, as posthog-js'
     * `custom_blocked_useragents`: bots, uptime checks. `call.posthog` does nothing in them and their
     * exceptions are not reported.
     */
    public val customBlockedUserAgents: MutableList<String> = config.list("customBlockedUserAgents").toMutableList()

    /**
     * Whether a call's distinct id and session id come from the tracing headers posthog-js sends
     * (`X-PostHog-Distinct-Id`, `X-PostHog-Session-Id`). They link server events to the browser's
     * person and session replay. The browser controls them: they are analytics context, never
     * authentication or authorization.
     */
    public var useTracingHeaders: Boolean = config.boolean("useTracingHeaders") ?: true

    /** Whether events carry the client's address as `$ip` (honouring Ktor's forwarded-headers plugins). */
    public var captureIp: Boolean = config.boolean("captureIp") ?: true

    /** Whether log lines written during a call carry [MDC_DISTINCT_ID] and [MDC_SESSION_ID] in the MDC. */
    public var logContext: Boolean = config.boolean("logContext") ?: true

    /** Whether exceptions of a call go to PostHog error tracking (see [captureStatus], [excludedExceptions]). */
    public var captureExceptions: Boolean = config.boolean("captureExceptions") ?: true

    /**
     * Whether events captured in a call (exceptions included) carry the flags the call has read
     * through `call.posthog.flags()`: `$feature/<key>` and `$active_feature_flags`, so breakdowns by
     * variant work for server events too. Flags are never evaluated just for this.
     */
    public var attachAccessedFlags: Boolean = config.boolean("attachAccessedFlags") ?: true

    /**
     * The exceptions never reported, with their subclasses: requests the client got wrong (bad
     * request, not found, content it cannot send or receive, too large) and clients that went away.
     * Add your own, or clear it.
     */
    public val excludedExceptions: MutableSet<KClass<out Throwable>> = defaultExcludedExceptions.toMutableSet()

    internal var distinctIdResolver: (ApplicationCall) -> String? = { null }
    internal var requestFilter: (ApplicationCall) -> Boolean = { true }
    internal var extraProperties: (ApplicationCall) -> Map<String, Any?> = { emptyMap() }
    internal var propertiesMap: (Map<String, Any>) -> Map<String, Any> = { it }
    internal var exceptionFilter: (Throwable, ApplicationCall) -> Boolean = { _, _ -> true }
    internal var captureStatus: (HttpStatusCode) -> Boolean = { it.value >= HttpStatusCode.InternalServerError.value }
    internal var flagContext: PostHogEvaluateFlagsOptions.Builder.(ApplicationCall) -> Unit = {}

    /**
     * The distinct id of the call's authenticated user, e.g. `{ it.principal<UserIdPrincipal>()?.name }`.
     * It wins over the tracing header; an id given to a capture or set by `call.posthog.identify` wins
     * over it. Called often: keep it cheap, without I/O; null means not known (yet).
     */
    public fun resolveDistinctId(resolver: (ApplicationCall) -> String?) {
        distinctIdResolver = resolver
    }

    /** Calls the plugin leaves alone (no context, no request properties, no exception capture), e.g. health checks. */
    public fun requestFilter(filter: (ApplicationCall) -> Boolean) {
        requestFilter = filter
    }

    /** Properties added to every event of a call; null values are left out. */
    public fun extraProperties(provider: (ApplicationCall) -> Map<String, Any?>) {
        extraProperties = provider
    }

    /** Changes or removes the request properties (`$current_url`, `$ip`…) before they go out. */
    public fun propertiesMap(mapper: (Map<String, Any>) -> Map<String, Any>) {
        propertiesMap = mapper
    }

    /** Which call failures are reported, on top of [captureStatus] and [excludedExceptions]. */
    public fun exceptionFilter(filter: (Throwable, ApplicationCall) -> Boolean) {
        exceptionFilter = filter
    }

    /** Reports a failed call by the status it answered with; by default 5xx only. */
    public fun captureStatus(predicate: (HttpStatusCode) -> Boolean) {
        captureStatus = predicate
    }

    /**
     * What a call's flags are evaluated with besides its distinct id: person properties, groups and
     * group properties that release conditions test, or the flags to evaluate (`flagKeys`) and
     * `onlyEvaluateLocally`. Called once per evaluation, with the call:
     *
     * ```kotlin
     * flagContext { call ->
     *     call.principal<Customer>()?.let { personProperty("plan", it.plan); group("company", it.companyId) }
     * }
     * ```
     */
    public fun flagContext(configure: PostHogEvaluateFlagsOptions.Builder.(ApplicationCall) -> Unit) {
        flagContext = configure
    }

    private companion object {
        val defaultExcludedExceptions: Set<KClass<out Throwable>> =
            setOf(
                io.ktor.server.plugins.BadRequestException::class,
                io.ktor.server.plugins.NotFoundException::class,
                io.ktor.server.plugins.ContentTransformationException::class,
                io.ktor.server.plugins.PayloadTooLargeException::class,
                java.util.concurrent.CancellationException::class,
                io.ktor.util.cio.ChannelWriteException::class,
                io.ktor.utils.io.ClosedWriteChannelException::class,
            )
    }
}

private fun ApplicationConfig.string(key: String) = propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }

private fun ApplicationConfig.boolean(key: String) = propertyOrNull(key)?.getString()?.toBooleanStrictOrNull()

private fun ApplicationConfig.int(key: String) = propertyOrNull(key)?.getString()?.toIntOrNull()

private fun ApplicationConfig.long(key: String) = propertyOrNull(key)?.getString()?.toLongOrNull()

// A list, or a comma-separated string (an environment variable).
private fun ApplicationConfig.list(key: String): List<String> {
    val property = propertyOrNull(key) ?: return emptyList()
    val values =
        try {
            property.getList()
        } catch (_: Exception) {
            property.getString().split(',')
        }
    return values.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
}

private fun ApplicationConfig.personProfiles(key: String): PersonProfiles? =
    string(key)?.let { value ->
        PersonProfiles.entries.firstOrNull { it.name.equals(value.replace('-', '_'), ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "posthog.$key is '$value'; expected one of ${PersonProfiles.entries.joinToString { it.name.lowercase() }}",
            )
    }

private val clientKey = AttributeKey<PostHogClient>("PostHogClient")

/** Marks a call from a blocked user agent: nothing is captured for it. */
internal val blockedKey = AttributeKey<Unit>("PostHogBlockedUserAgent")

/**
 * The PostHog plugin for Ktor servers.
 *
 * - Owns a client of PostHog's official JVM SDK (`com.posthog:posthog-server`) for the lifetime of
 *   the application and closes it, flushing what is queued, when the application stops; or uses a
 *   [PostHogClient] made before the application ([PostHogPluginConfig.client]).
 * - Gives every call a [PostHogContext]: the browser's distinct id and session id (tracing
 *   headers) or the authenticated user ([PostHogPluginConfig.resolveDistinctId]), and request
 *   properties (`$current_url`, `$request_method`, `$request_path`, `$route`, `$user_agent`, `$ip`)
 *   that every event captured during the call carries, also on other threads and through direct
 *   SDK calls.
 * - Puts the ids into the MDC of the call's log lines, for PostHog Logs.
 * - Reports the exceptions of failed calls to error tracking.
 * - Evaluates a call's feature flags once, on first use (`call.posthog.flags()`), for gating
 *   routes ([featureFlag]) and for posthog-js' bootstrap ([posthogFlags], `call.posthog.bootstrap`).
 *
 * ```kotlin
 * install(PostHog) {
 *     projectToken = "phc_…"
 *     host = "https://eu.i.posthog.com"
 *     resolveDistinctId { it.principal<UserIdPrincipal>()?.name }
 * }
 * ```
 */
public val PostHog: ApplicationPlugin<PostHogPluginConfig> =
    createApplicationPlugin("PostHog", "posthog", ::PostHogPluginConfig) {
        val config = pluginConfig
        val shared = config.client
        val client = shared ?: PostHogClient.create(config)
        application.attributes.put(clientKey, client)
        // A shared client is closed by whoever made it.
        if (shared == null) application.monitor.subscribe(ApplicationStopped) { client.close() }

        // The route template is known once routing has picked a route.
        application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { routingCall ->
            routingCall.attributes.getOrNull(PostHogCallState.key)?.route = routingCall.route
        }

        on(CallScope) { call, proceed ->
            if (config.isBlockedUserAgent(call)) {
                call.attributes.put(blockedKey, Unit)
                return@on proceed()
            }
            if (!config.requestFilter(call)) return@on proceed()
            val state = PostHogCallState(call, config, client)
            call.attributes.put(PostHogCallState.key, state)
            try {
                withContext(state.context) { proceed() }
            } catch (failure: Throwable) {
                // Reported (or not) by CallOutcome, once the status the call ends with is known.
                state.failure = failure
                throw failure
            }
        }

        // Outside every other plugin, StatusPages included: a failure it answered has its status
        // by now, one nobody handled becomes a 500.
        on(CallOutcome) { call, proceed ->
            try {
                proceed()
            } catch (unhandled: Throwable) {
                call.attributes.getOrNull(PostHogCallState.key)?.let { state ->
                    config.reportFailure(state, state.failure ?: unhandled, HttpStatusCode.InternalServerError, call)
                }
                throw unhandled
            }
            val state = call.attributes.getOrNull(PostHogCallState.key) ?: return@on
            val failure = state.failure ?: return@on
            config.reportFailure(state, failure, call.response.status() ?: HttpStatusCode.InternalServerError, call)
        }
    }

private fun PostHogPluginConfig.reportFailure(
    state: PostHogCallState,
    failure: Throwable,
    status: HttpStatusCode,
    call: ApplicationCall,
) {
    val report =
        captureExceptions &&
            captureStatus(status) &&
            excludedExceptions.none { it.isInstance(failure) } &&
            exceptionFilter(failure, call)
    if (report) {
        try {
            state.captureFailure(failure)
        } catch (e: Exception) {
            LoggerFactory.getLogger(PostHog::class.java).warn("Could not report a failed call to PostHog", e)
        }
    }
}

/**
 * Wraps the whole call in a phase of its own, placed before every phase the pipeline has when the
 * plugin is installed. StatusPages handles failures in a phase it puts before `Setup`; whichever is
 * installed first, this one ends up outside it.
 */
private object CallOutcome : Hook<suspend (ApplicationCall, suspend () -> Unit) -> Unit> {
    private val phase = PipelinePhase("PostHogCallOutcome")

    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall, suspend () -> Unit) -> Unit,
    ) {
        pipeline.insertPhaseBefore(pipeline.items.first(), phase)
        pipeline.intercept(phase) { handler(context) { proceed() } }
    }
}

/** Wraps the rest of a call (routing included) at the `Plugins` phase. */
private object CallScope : Hook<suspend (ApplicationCall, suspend () -> Unit) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (ApplicationCall, suspend () -> Unit) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) { handler(context) { proceed() } }
    }
}

private fun PostHogPluginConfig.isBlockedUserAgent(call: ApplicationCall): Boolean {
    if (customBlockedUserAgents.isEmpty()) return false
    val userAgent = call.request.headers[HttpHeaders.UserAgent] ?: return false
    return customBlockedUserAgents.any { userAgent.contains(it, ignoreCase = true) }
}

internal fun PostHogClientConfig.buildSdk(client: PostHogClient): PostHogInterface? {
    val token = projectToken ?: return null
    val builder = PostHogSdkConfig.builder(token).host(host)
    secretKey?.let { builder.personalApiKey(it).localEvaluation(localEvaluation) }
    flushAt?.let { builder.flushAt(it) }
    flushIntervalSeconds?.let { builder.flushIntervalSeconds(it) }
    maxQueueSize?.let { builder.maxQueueSize(it) }
    maxBatchSize?.let { builder.maxBatchSize(it) }
    pollIntervalSeconds?.let { builder.pollIntervalSeconds(it) }
    releaseIdentifier?.let { builder.releaseIdentifier(it) }
    if (evaluationContexts.isNotEmpty()) builder.evaluationContexts(evaluationContexts.toList())
    if (inAppIncludes.isNotEmpty()) builder.inAppIncludes(inAppIncludes.toList())
    builder
        .debug(debug)
        .sendFeatureFlagEvent(sendFeatureFlagEvent)
        .captureUncaughtExceptions(captureUncaughtExceptions)
        .sdk()
    val sdkConfig = builder.build()
    sdkConfig.addBeforeSend(EventPipeline(client, beforeSendHooks.toList(), personProfiles, disableGeoip))
    return PostHogSdk.with(sdkConfig)
}

/** The PostHog client of this application; installing the [PostHog] plugin creates it. */
public val Application.posthog: PostHogClient
    get() =
        attributes.getOrNull(clientKey)
            ?: error("PostHog is not installed: add install(PostHog) to the application module")

/** The header posthog-js sends its distinct id in. */
public const val DISTINCT_ID_HEADER: String = PostHogRequestContext.DISTINCT_ID_HEADER

/** The header posthog-js sends its session id in. */
public const val SESSION_ID_HEADER: String = PostHogRequestContext.SESSION_ID_HEADER
