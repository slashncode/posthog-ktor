package io.github.slashncode.posthog.ktor

import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * A reverse proxy to PostHog at [path], so posthog-js sends to the app's own origin
 * (`api_host: '/ingest'`) and ad blockers that drop requests to PostHog's domains let it through.
 * `<path>/static/…` goes to [assetsHost], everything else (events, flags, recordings, surveys) to
 * [apiHost].
 *
 * - Bodies stream through in both directions, compressed as they came.
 * - The visitor's cookies, `Authorization` and hop-by-hop headers stay behind; `Set-Cookie` from
 *   PostHog does not come back.
 * - PostHog gets the visitor's address in `X-Forwarded-For` (from Ktor's forwarded-headers plugins
 *   if installed), for GeoIP.
 *
 * ```kotlin
 * routing { posthogProxy("/ingest") }
 * // posthog.init('phc_…', { api_host: '/ingest', ui_host: 'https://eu.posthog.com' })
 * ```
 *
 * @param apiHost PostHog's ingestion host; the [PostHog] plugin's `host` if null.
 * @param assetsHost where posthog-js' scripts come from; for PostHog's clouds their assets host
 *   (`https://eu-assets.i.posthog.com`, `https://us-assets.i.posthog.com`), else [apiHost].
 */
public fun Route.posthogProxy(
    path: String = "/ingest",
    apiHost: String? = null,
    assetsHost: String? = null,
): Route =
    route(path) {
        val proxy = lazy { PostHogProxy() }
        route("{$TAIL...}") {
            handle {
                val api = (apiHost ?: call.application.posthog.host).trimEnd('/')
                proxy.value.forward(call, api, assetsHost?.trimEnd('/') ?: assetsHostFor(api))
            }
        }
    }

private const val TAIL = "posthogProxyPath"

/** PostHog's clouds serve posthog-js from a host of its own; a self-hosted PostHog serves it itself. */
internal fun assetsHostFor(apiHost: String): String {
    val cloud = Regex("^(https://)(eu|us)\\.i\\.posthog\\.com$").matchEntire(apiHost) ?: return apiHost
    return "${cloud.groupValues[1]}${cloud.groupValues[2]}-assets.i.posthog.com"
}

private class PostHogProxy {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    suspend fun forward(
        call: ApplicationCall,
        apiHost: String,
        assetsHost: String,
    ) {
        val target = target(call, apiHost, assetsHost)
        val request =
            HttpRequest
                .newBuilder(target)
                .timeout(Duration.ofSeconds(60))
                .version(if (target.scheme == "https") HttpClient.Version.HTTP_2 else HttpClient.Version.HTTP_1_1)
                .method(call.request.httpMethod.value, body(call))
        call.request.headers.forEach { name, values ->
            if (name.lowercase() !in droppedRequestHeaders) values.forEach { request.header(name, it) }
        }
        request.header(HttpHeaders.XForwardedFor, call.request.origin.remoteAddress)

        val response =
            try {
                client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream()).await()
            } catch (e: HttpTimeoutException) {
                log.warn("PostHog did not answer {} in time", target, e)
                return call.respondText("PostHog did not answer in time", status = HttpStatusCode.GatewayTimeout)
            } catch (e: IOException) {
                log.warn("Could not reach PostHog at {}", target, e)
                return call.respondText("PostHog is unreachable", status = HttpStatusCode.BadGateway)
            }
        call.respond(Forwarded(response))
    }

    // The path after the proxy's own, exactly as the browser wrote it (encoding, trailing slash), and the query.
    private fun target(
        call: ApplicationCall,
        apiHost: String,
        assetsHost: String,
    ): URI {
        val tail =
            call.parameters
                .getAll(TAIL)
                .orEmpty()
                .filter { it.isNotEmpty() }
        val raw = call.request.path()
        val rest =
            raw
                .split('/')
                .filter { it.isNotEmpty() }
                .takeLast(tail.size)
                .joinToString("/")
        val slash = if (raw.endsWith('/') && rest.isNotEmpty()) "/" else ""
        val host = if (rest.startsWith("static/")) assetsHost else apiHost
        val query =
            call.request
                .queryString()
                .takeIf { it.isNotEmpty() }
                ?.let { "?$it" }
                .orEmpty()
        return URI("$host/$rest$slash$query")
    }

    private suspend fun body(call: ApplicationCall): HttpRequest.BodyPublisher =
        when (call.request.httpMethod) {
            HttpMethod.Get, HttpMethod.Head, HttpMethod.Options, HttpMethod.Delete -> HttpRequest.BodyPublishers.noBody()
            else -> {
                val channel = call.receiveChannel()
                HttpRequest.BodyPublishers.ofInputStream { channel.toInputStream() }
            }
        }

    private class Forwarded(
        private val response: HttpResponse<InputStream>,
    ) : OutgoingContent.ReadChannelContent() {
        override val status: HttpStatusCode = HttpStatusCode.fromValue(response.statusCode())
        override val contentType: ContentType? =
            response
                .headers()
                .firstValue(HttpHeaders.ContentType)
                .map(ContentType::parse)
                .orElse(null)
        override val contentLength: Long? =
            response.headers().firstValueAsLong(HttpHeaders.ContentLength).let { if (it.isPresent) it.asLong else null }
        override val headers: Headers =
            Headers.build {
                response.headers().map().forEach { (name, values) ->
                    if (name.lowercase() !in droppedResponseHeaders) appendAll(name, values)
                }
            }

        override fun readFrom(): ByteReadChannel = response.body().toByteReadChannel(Dispatchers.IO)
    }

    private companion object {
        val log = LoggerFactory.getLogger(PostHogProxy::class.java)!!

        // Hop-by-hop headers, what the JDK client sets itself, and what is the visitor's business only.
        val hopByHop =
            setOf("connection", "keep-alive", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "http2-settings")

        val droppedRequestHeaders =
            hopByHop +
                setOf(
                    "host",
                    "content-length",
                    "expect",
                    "cookie",
                    "authorization",
                    "proxy-authorization",
                    "x-forwarded-for",
                    "x-forwarded-host",
                    "x-forwarded-proto",
                    "x-real-ip",
                    "forwarded",
                )

        val droppedResponseHeaders = hopByHop + setOf("content-length", "content-type", "set-cookie", ":status")
    }
}
