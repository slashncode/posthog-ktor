package io.github.slashncode.posthog.ktor.opentelemetry

import com.posthog.server.otel.PostHogContextSpanProcessor
import io.github.slashncode.posthog.ktor.MDC_DISTINCT_ID
import io.github.slashncode.posthog.ktor.MDC_SESSION_ID
import io.github.slashncode.posthog.ktor.PostHog
import io.github.slashncode.posthog.ktor.posthog
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Configuration of the [PostHogOpenTelemetry] plugin; values come from the `posthog.openTelemetry`
 * section of the application config first.
 */
public class PostHogOpenTelemetryConfig internal constructor(
    config: ApplicationConfig,
) {
    /** `service.name`: how PostHog groups the spans and logs of this service. */
    public var serviceName: String = config.propertyOrNull("serviceName")?.getString() ?: "ktor-app"

    /** `service.version`, if set. */
    public var serviceVersion: String? = config.propertyOrNull("serviceVersion")?.getString()

    /** `deployment.environment.name`, e.g. `production`, if set. */
    public var environment: String? = config.propertyOrNull("environment")?.getString()

    /** Further resource attributes of every span and log record. */
    public val resourceAttributes: MutableMap<String, String> = mutableMapOf()

    /** Whether spans go to PostHog's tracing (`/i/v1/traces`). */
    public var traces: Boolean = config.propertyOrNull("traces")?.getString()?.toBooleanStrictOrNull() ?: true

    /** Whether log lines (Logback) go to PostHog Logs (`/i/v1/logs`), with the call's person and session. */
    public var logs: Boolean = config.propertyOrNull("logs")?.getString()?.toBooleanStrictOrNull() ?: true

    /** Whether AI spans (`gen_ai.*` and others) go to PostHog's AI observability (`/i/v0/ai/otel`). */
    public var ai: Boolean = config.propertyOrNull("ai")?.getString()?.toBooleanStrictOrNull() ?: true

    /** AI spans without prompts, outputs and errors; the PostHog plugin's `privacyMode` if null. */
    public var privacyMode: Boolean? = config.propertyOrNull("privacyMode")?.getString()?.toBooleanStrictOrNull()

    /** Whether the Ktor server instrumentation is installed: a server span per call. */
    public var instrumentServer: Boolean = config.propertyOrNull("instrumentServer")?.getString()?.toBooleanStrictOrNull() ?: true

    /** Which traces are recorded; all by default, honouring the caller's decision (`traceparent`). PostHog does not sample. */
    public var sampler: Sampler = Sampler.parentBased(Sampler.alwaysOn())

    /** How often ended spans and log records are sent, in milliseconds (posthog-node's `flushIntervalMs`). */
    public var flushIntervalMs: Long = config.propertyOrNull("flushIntervalMs")?.getString()?.toLongOrNull() ?: 5_000

    /** How many spans or log records are sent in one request at most (`maxExportBatchSize`). */
    public var maxExportBatchSize: Int = config.propertyOrNull("maxExportBatchSize")?.getString()?.toIntOrNull() ?: 512

    /** How many ended spans or log records wait to be sent at most; beyond it they are dropped (`maxQueueSize`). */
    public var maxQueueSize: Int = config.propertyOrNull("maxQueueSize")?.getString()?.toIntOrNull() ?: 2_048

    /** How long stopping the application waits for spans and logs to be sent, in milliseconds. */
    public var shutdownTimeoutMs: Long = config.propertyOrNull("shutdownTimeoutMs")?.getString()?.toLongOrNull() ?: 10_000

    internal var beforeSpanSend: List<(SpanData) -> SpanData?> = emptyList()
    internal var tracerProvider: SdkTracerProviderBuilder.() -> Unit = {}
    internal var loggerProvider: SdkLoggerProviderBuilder.() -> Unit = {}

    /**
     * Changes or drops a span before it goes to PostHog's tracing, as posthog-node's
     * `beforeSpanSend`: return the span (wrap it in `DelegatingSpanData` to change it) or null.
     * Hooks run in order; one that throws drops the span.
     */
    public fun beforeSpanSend(hook: (SpanData) -> SpanData?) {
        beforeSpanSend = beforeSpanSend + hook
    }

    /** Adds to the tracer provider, e.g. a second exporter: `addSpanProcessor(…)`. */
    public fun tracerProvider(configure: SdkTracerProviderBuilder.() -> Unit) {
        tracerProvider = configure
    }

    /** Adds to the logger provider. */
    public fun loggerProvider(configure: SdkLoggerProviderBuilder.() -> Unit) {
        loggerProvider = configure
    }
}

private val openTelemetryKey = AttributeKey<OpenTelemetry>("PostHogOpenTelemetry")

/**
 * OpenTelemetry for PostHog: an OpenTelemetry SDK that sends to the PostHog project of the
 * [PostHog] plugin (installed first) without a collector.
 *
 * - Traces to PostHog's tracing, every span inside a call with the call's `posthogDistinctId` and
 *   `sessionId`, through the official SDK's `PostHogContextSpanProcessor`; a server span per call
 *   (Ktor instrumentation) and W3C `traceparent` across services.
 * - Logs: Logback's lines to PostHog Logs, with the MDC's `posthogDistinctId` and `sessionId` and the
 *   trace they belong to.
 * - AI spans of model libraries that use OpenTelemetry (Koog, Spring AI) to AI observability, see
 *   [PostHogAiSpanProcessor].
 *
 * While PostHog is off (no project token) nothing is set up and [Application.openTelemetry] is
 * OpenTelemetry's no-op.
 *
 * ```kotlin
 * install(PostHog) { projectToken = "phc_…" }
 * install(PostHogOpenTelemetry) { serviceName = "orders" }
 * ```
 */
public val PostHogOpenTelemetry: ApplicationPlugin<PostHogOpenTelemetryConfig> =
    createApplicationPlugin("PostHogOpenTelemetry", "posthog.openTelemetry", ::PostHogOpenTelemetryConfig) {
        val config = pluginConfig
        check(application.pluginOrNull(PostHog) != null) { "Install PostHog before PostHogOpenTelemetry" }
        val posthog = application.posthog
        val token = posthog.projectToken
        if (token == null) {
            application.attributes.put(openTelemetryKey, OpenTelemetry.noop())
            return@createApplicationPlugin
        }
        val sdk = config.build(token, posthog.host, config.privacyMode ?: posthog.privacyMode, posthog.sdk)
        application.attributes.put(openTelemetryKey, sdk)
        val appender = if (config.logs) LogbackBridge.attach(sdk) else null
        if (config.instrumentServer) application.install(KtorServerTelemetry) { setOpenTelemetry(sdk) }
        application.monitor.subscribe(ApplicationStopped) {
            appender?.detach()
            sdk.shutdown().join(config.shutdownTimeoutMs, TimeUnit.MILLISECONDS)
        }
    }

/** The OpenTelemetry the [PostHogOpenTelemetry] plugin set up, for instrumenting more (an `HttpClient`, a model library). */
public val Application.openTelemetry: OpenTelemetry
    get() =
        attributes.getOrNull(openTelemetryKey)
            ?: error("PostHogOpenTelemetry is not installed: add install(PostHogOpenTelemetry) after install(PostHog)")

/** A tracer of the [PostHogOpenTelemetry] plugin's OpenTelemetry, for spans of your own (`withSpan`). */
public val Application.posthogTracer: Tracer get() = openTelemetry.getTracer(INSTRUMENTATION_NAME)

internal const val INSTRUMENTATION_NAME = "io.github.slashncode.posthog.ktor"

private fun PostHogOpenTelemetryConfig.build(
    token: String,
    host: String,
    privacyMode: Boolean,
    posthogSdk: com.posthog.server.PostHogInterface?,
): OpenTelemetrySdk {
    val base = host.trimEnd('/')
    val flushInterval = Duration.ofMillis(flushIntervalMs)
    val resource =
        Resource.getDefault().merge(
            Resource.create(
                Attributes
                    .builder()
                    .put("service.name", serviceName)
                    .apply {
                        serviceVersion?.let { put("service.version", it) }
                        environment?.let { put("deployment.environment.name", it) }
                        resourceAttributes.forEach { (key, value) -> put(key, value) }
                    }.build(),
            ),
        )
    val tracers =
        SdkTracerProvider
            .builder()
            .setResource(resource)
            .setSampler(sampler)
            .apply {
                // Writes posthogDistinctId and sessionId on every span started inside a call.
                posthogSdk?.let { addSpanProcessor(PostHogContextSpanProcessor(it)) }
                if (traces) {
                    val exporter =
                        OtlpHttpSpanExporter
                            .builder()
                            .setEndpoint("$base/i/v1/traces")
                            .addHeader("Authorization", "Bearer $token")
                            .build()
                    addSpanProcessor(
                        BatchSpanProcessor
                            .builder(BeforeSpanSend(exporter, beforeSpanSend))
                            .setScheduleDelay(flushInterval)
                            .setMaxExportBatchSize(maxExportBatchSize)
                            .setMaxQueueSize(maxQueueSize)
                            .build(),
                    )
                }
                if (ai) addSpanProcessor(PostHogAiSpanProcessor(token, host, privacyMode, flushInterval))
            }.apply(tracerProvider)
            .build()
    val loggers =
        SdkLoggerProvider
            .builder()
            .setResource(resource)
            .apply {
                if (logs) {
                    val exporter =
                        OtlpHttpLogRecordExporter
                            .builder()
                            .setEndpoint("$base/i/v1/logs")
                            .addHeader("Authorization", "Bearer $token")
                            .build()
                    addLogRecordProcessor(
                        BatchLogRecordProcessor
                            .builder(exporter)
                            .setScheduleDelay(flushInterval)
                            .setMaxExportBatchSize(maxExportBatchSize)
                            .setMaxQueueSize(maxQueueSize)
                            .build(),
                    )
                }
            }.apply(loggerProvider)
            .build()
    return OpenTelemetrySdk
        .builder()
        .setTracerProvider(tracers)
        .setLoggerProvider(loggers)
        .setPropagators(
            ContextPropagators.create(
                TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance()),
            ),
        ).build()
}

private class BeforeSpanSend(
    private val delegate: SpanExporter,
    private val hooks: List<(SpanData) -> SpanData?>,
) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        if (hooks.isEmpty()) return delegate.export(spans)
        val kept =
            spans.mapNotNull { span ->
                hooks.fold<(SpanData) -> SpanData?, SpanData?>(span) { current, hook ->
                    current?.let {
                        try {
                            hook(it)
                        } catch (e: Exception) {
                            log.warn("A beforeSpanSend hook failed; the span '{}' is dropped", it.name, e)
                            null
                        }
                    }
                }
            }
        return if (kept.isEmpty()) CompletableResultCode.ofSuccess() else delegate.export(kept)
    }

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()

    private companion object {
        val log = LoggerFactory.getLogger(BeforeSpanSend::class.java)!!
    }
}

// Logback is optional: apps with another SLF4J backend get traces and AI spans, but no logs.
private object LogbackBridge {
    fun attach(openTelemetry: OpenTelemetry): Detachable? =
        try {
            LogbackAppender.attach(openTelemetry)
        } catch (_: NoClassDefFoundError) {
            LoggerFactory
                .getLogger(
                    PostHogOpenTelemetryConfig::class.java,
                ).warn("Logback is not on the classpath; logs do not go to PostHog")
            null
        }
}

internal fun interface Detachable {
    fun detach()
}

private object LogbackAppender {
    fun attach(openTelemetry: OpenTelemetry): Detachable? {
        val context = LoggerFactory.getILoggerFactory() as? ch.qos.logback.classic.LoggerContext ?: return null
        val appender =
            io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender().apply {
                this.context = context
                name = "posthog-logs"
                setMdcAttributesIncluded("$MDC_DISTINCT_ID,$MDC_SESSION_ID")
                setOpenTelemetry(openTelemetry)
                start()
            }
        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        root.addAppender(appender)
        return Detachable {
            root.detachAppender(appender)
            appender.stop()
        }
    }
}
