package io.github.slashncode.posthog.ktor

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** A stand-in for PostHog's hosts that records what reaches it and answers like PostHog would. */
private class Upstream : AutoCloseable {
    class Request(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        fun header(name: String) =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.single()
    }

    val requests = CopyOnWriteArrayList<Request>()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    val uri = exchange.requestURI
                    requests +=
                        Request(
                            exchange.requestMethod,
                            uri.rawPath,
                            uri.rawQuery,
                            exchange.requestHeaders.toMap(),
                            exchange.requestBody.readBytes(),
                        )
                    val body = if (uri.rawPath.endsWith(".js")) "console.log('posthog')" else """{"status":1}"""
                    exchange.responseHeaders.add(
                        "Content-Type",
                        if (uri.rawPath.endsWith(".js")) "application/javascript" else "application/json",
                    )
                    exchange.responseHeaders.add("Cache-Control", "public, max-age=60")
                    exchange.responseHeaders.add("Set-Cookie", "tracker=1; Path=/")
                    exchange.sendResponseHeaders(200, body.length.toLong())
                    exchange.responseBody.write(body.encodeToByteArray())
                } finally {
                    exchange.close()
                }
            }
            start()
        }
    val host = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}

class PostHogProxyTest :
    FunSpec({
        test("events go to the API host and scripts to the assets host, streamed, without the visitor's cookies") {
            Upstream().use { api ->
                Upstream().use { assets ->
                    val payload = Random(7).nextBytes(3 * 1024 * 1024)
                    testApplication {
                        install(PostHog)
                        routing {
                            posthogProxy("/ingest", apiHost = api.host, assetsHost = assets.host)
                            route("/api") { posthogProxy("/ph", apiHost = api.host, assetsHost = assets.host) }
                        }

                        val response =
                            client.post("/ingest/e/?compression=gzip-js&ip=1") {
                                contentType(ContentType.Text.Plain)
                                header(HttpHeaders.Cookie, "session=secret")
                                header(HttpHeaders.Authorization, "Bearer secret")
                                header(HttpHeaders.UserAgent, "Firefox")
                                header(HttpHeaders.XForwardedFor, "198.51.100.1")
                                setBody(payload)
                            }
                        response.status shouldBe HttpStatusCode.OK
                        response.bodyAsText() shouldBe """{"status":1}"""
                        response.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=60"
                        response.headers[HttpHeaders.SetCookie] shouldBe null

                        val script = client.get("/ingest/static/array.js")
                        script.bodyAsBytes().decodeToString() shouldBe "console.log('posthog')"
                        script.headers[HttpHeaders.ContentType] shouldBe "application/javascript"

                        client.post("/api/ph/flags/?v=2") { setBody("{}") }.status shouldBe HttpStatusCode.OK
                    }

                    val event = api.requests[0]
                    event.method shouldBe "POST"
                    event.path shouldBe "/e/"
                    event.query shouldBe "compression=gzip-js&ip=1"
                    event.body.contentEquals(payload) shouldBe true
                    event.header("Cookie") shouldBe null
                    event.header("Authorization") shouldBe null
                    event.header("User-Agent") shouldBe "Firefox"
                    // The address the app sees, not what the browser claims (no forwarded-headers plugin here).
                    event.header("X-Forwarded-For") shouldBe "localhost"
                    assets.requests.single().path shouldBe "/static/array.js"
                    api.requests[1].path shouldBe "/flags/"
                    api.requests[1].query shouldBe "v=2"
                }
            }
        }

        test("an unreachable PostHog is a 502; the cloud hosts have their assets hosts") {
            val gone = Upstream().also { it.close() }
            testApplication {
                routing { posthogProxy(apiHost = gone.host) }
                client.post("/ingest/e/") { setBody("{}") }.status shouldBe HttpStatusCode.BadGateway
            }

            assetsHostFor("https://eu.i.posthog.com") shouldBe "https://eu-assets.i.posthog.com"
            assetsHostFor("https://us.i.posthog.com") shouldBe "https://us-assets.i.posthog.com"
            assetsHostFor("https://posthog.example.com") shouldBe "https://posthog.example.com"
        }
    })
