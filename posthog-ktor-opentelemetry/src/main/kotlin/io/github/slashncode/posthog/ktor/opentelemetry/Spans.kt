package io.github.slashncode.posthog.ktor.opentelemetry

import io.ktor.client.HttpClientConfig
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.extension.kotlin.getOpenTelemetryContext
import io.opentelemetry.instrumentation.ktor.v3_0.KtorClientTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Runs [block] in a new span named [name], a child of the coroutine's current span (the call's
 * server span, an outer `withSpan`), as posthog-node's `withSpan`: the span ends when the block
 * returns; an exception is recorded, sets the status to error and is rethrown. Keep names
 * low-cardinality (`charge card`, not `charge card 4711`) and put values in [attributes].
 *
 * ```kotlin
 * application.posthogTracer.withSpan("charge card") { span ->
 *     span.setAttribute("order.id", order.id)
 *     payments.charge(order)
 * }
 * ```
 */
public suspend fun <T> Tracer.withSpan(
    name: String,
    kind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    block: suspend CoroutineScope.(Span) -> T,
): T {
    val parent = currentCoroutineContext().getOpenTelemetryContext()
    val span =
        spanBuilder(name)
            .setParent(parent)
            .setSpanKind(kind)
            .setAllAttributes(attributes)
            .startSpan()
    return try {
        withContext(parent.with(span).asContextElement()) { block(span) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        span.recordException(e)
        span.setStatus(StatusCode.ERROR)
        throw e
    } finally {
        span.end()
    }
}

/**
 * Traces the requests of this `HttpClient` with [openTelemetry] (usually `application.openTelemetry`):
 * a client span per request, and W3C `traceparent` to the service called, so its spans join the
 * trace. Pair it with `PostHogTracingHeaders` to pass the person and session on too.
 */
public fun HttpClientConfig<*>.posthogOpenTelemetry(openTelemetry: OpenTelemetry) {
    install(KtorClientTelemetry) { setOpenTelemetry(openTelemetry) }
}
