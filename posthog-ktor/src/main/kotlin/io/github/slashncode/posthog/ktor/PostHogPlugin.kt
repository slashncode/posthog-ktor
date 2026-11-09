package io.github.slashncode.posthog.ktor

import com.posthog.server.PostHogEvaluateFlagsOptions
import com.posthog.server.PostHogInterface
import com.posthog.server.PostHogRequestContext
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
    public var projectToken: String? = config.string("projectToken")

    /** The ingestion host of the project's region. */
    public var host: String = config.string("host") ?: POSTHOG_US_HOST

    /**
     * A key that may read the project's flag definitions: the Feature Flags Secure API Key (`phs_…`)
     * or a personal API key with `feature_flag:read`. With it, flags are evaluated locally,
     * without a request to PostHog per evaluation.
     */
    public var secretKey: String? = config.string("secretKey")

    /**
     * A client created elsewhere, used instead of one built from this configuration. The plugin
     * does not close it; whoever created it does.
     */
    public var client: PostHogInterface? = null

    /**
     * Whether a call's distinct id and session id come from the tracing headers posthog-js sends
     * (`X-PostHog-Distinct-Id`, `X-PostHog-Session-Id`). They link server events to the browser's
     * person and session replay. The browser controls them: they are analytics context, never
     * authentication or authorization.
     */
    public var useTracingHeaders: Boolean = config.boolean("useTracingHeaders") ?: true

    /** Whether events carry the client's address as `$ip` (honouring Ktor's forwarded-headers plugins). */
    public var captureIp: Boolean = config.boolean("captureIp") ?: true

    /**
     * Whether an event without any distinct id is still sent, under a random id and without a person
     * profile (`$process_person_profile: false`), as the official SDK does. Off, such events are
     * dropped: with posthog-js sending its ids only after the visitor agreed to analytics, that
     * captures nothing about visitors who did not.
     */
    public var captureWithoutDistinctId: Boolean = config.boolean("captureWithoutDistinctId") ?: true

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

    internal var sdk: PostHogSdkConfig.Builder.() -> Unit = {}
    internal var distinctIdResolver: (ApplicationCall) -> String? = { null }
    internal var requestFilter: (ApplicationCall) -> Boolean = { true }
    internal var extraProperties: (ApplicationCall) -> Map<String, Any?> = { emptyMap() }
    internal var propertiesMap: (Map<String, Any>) -> Map<String, Any> = { it }
    internal var exceptionFilter: (Throwable, ApplicationCall) -> Boolean = { _, _ -> true }
    internal var captureStatus: (HttpStatusCode) -> Boolean = { it.value >= HttpStatusCode.InternalServerError.value }
    internal var flagContext: PostHogEvaluateFlagsOptions.Builder.(ApplicationCall) -> Unit = {}

    /** Every other option of the official SDK (batching, proxy, flag polling…), applied last. */
    public fun sdk(configure: PostHogSdkConfig.Builder.() -> Unit) {
        sdk = configure
    }

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

        fun ApplicationConfig.string(key: String) = propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }

        fun ApplicationConfig.boolean(key: String) = propertyOrNull(key)?.getString()?.toBooleanStrictOrNull()
    }
}

private val clientKey = AttributeKey<PostHogClient>("PostHogClient")

/**
 * The PostHog plugin for Ktor servers.
 *
 * - Owns a client of PostHog's official JVM SDK (`com.posthog:posthog-server`) for the lifetime of
 *   the application and closes it, flushing what is queued, when the application stops.
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
        val owned = config.client == null
        val sdk = config.client ?: config.buildClient()
        val client = PostHogClient(sdk, config.captureWithoutDistinctId)
        application.attributes.put(clientKey, client)
        if (owned && sdk != null) {
            application.monitor.subscribe(ApplicationStopped) { sdk.close() }
        }

        // The route template is known once routing has picked a route.
        application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { routingCall ->
            routingCall.attributes.getOrNull(PostHogCallState.key)?.route = routingCall.route
        }

        on(CallScope) { call, proceed ->
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

/** The header posthog-js sends its distinct id in. */
public const val DISTINCT_ID_HEADER: String = PostHogRequestContext.DISTINCT_ID_HEADER

/** The header posthog-js sends its session id in. */
public const val SESSION_ID_HEADER: String = PostHogRequestContext.SESSION_ID_HEADER
