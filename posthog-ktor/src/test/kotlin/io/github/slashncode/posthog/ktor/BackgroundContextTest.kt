package io.github.slashncode.posthog.ktor

import com.posthog.server.otel.PostHogContextSpanProcessor
import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.seconds

private fun PostHogFake.event(name: String) = sent().single { it.string("event") == name }

class BackgroundContextTest :
    FunSpec({
        test("work outside a call captures with the context it is given, across threads") {
            PostHogFake().use { fake ->
                postHogTest(fake, {
                    install(PostHog)
                    val posthog = posthog
                    routing {
                        get("/run") {
                            posthog.withContext(distinctId = "user-1", sessionId = "session-1", properties = mapOf("job" to "nightly")) {
                                withContext(Dispatchers.IO) { posthog.capture("job_ran") }
                                PostHogContext.current()!!.distinctId shouldBe "user-1"
                            }
                            // A message from a queue: the sender's headers name the person.
                            val headers = mapOf("x-posthog-distinct-id" to "user-2", "X-PostHog-Session-Id" to listOf("session-2"))
                            posthog.withContext(PostHogContext.fromHeaders(headers)!!) { posthog.capture("message_handled") }
                            PostHogContext.fromHeaders(mapOf("Other" to "x")) shouldBe null
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/run")
                }

                eventually(10.seconds) { fake.sent().size shouldBe 2 }
                val job = fake.event("job_ran")
                job.string("distinct_id") shouldBe "user-1"
                job.property("\$session_id") shouldBe "session-1"
                job.property("job") shouldBe "nightly"
                fake.event("message_handled").string("distinct_id") shouldBe "user-2"
                fake.event("message_handled").property("\$session_id") shouldBe "session-2"
            }
        }

        test("a call's context goes to a queue as headers, and as a snapshot to work that outlives the call") {
            PostHogFake().use { fake ->
                val outlived = CompletableDeferred<Unit>()
                postHogTest(fake, {
                    install(PostHog)
                    routing {
                        get("/order") {
                            val context = PostHogContext.current()!!
                            context.toHeaders() shouldBe mapOf(DISTINCT_ID_HEADER to "anon-1", SESSION_ID_HEADER to "session-1")
                            val snapshot = context.snapshot()
                            call.application.launch(snapshot) {
                                yield()
                                call.application.posthog.capture("order_followed_up")
                                outlived.complete(Unit)
                            }
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/order?id=7") {
                        header(DISTINCT_ID_HEADER, "anon-1")
                        header(SESSION_ID_HEADER, "session-1")
                    }
                    outlived.await()
                }

                eventually(10.seconds) { fake.sent().size shouldBe 1 }
                val event = fake.event("order_followed_up")
                event.string("distinct_id") shouldBe "anon-1"
                event.property("\$session_id") shouldBe "session-1"
                event.property("\$request_path") shouldBe "/order"
            }
        }

        test("with the SDK's span processor, server spans and the spans inside a call carry the PostHog ids") {
            val exporter = InMemorySpanExporter.create()
            testApplication {
                environment { config = MapApplicationConfig("posthog.projectToken" to "phc_test", "posthog.host" to "http://localhost:1") }
                application {
                    install(PostHog)
                    val tracing =
                        OpenTelemetrySdk
                            .builder()
                            .setTracerProvider(
                                SdkTracerProvider
                                    .builder()
                                    .addSpanProcessor(PostHogContextSpanProcessor(posthog.sdk!!))
                                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                                    .build(),
                            ).build()
                    install(KtorServerTelemetry) { setOpenTelemetry(tracing) }
                    routing {
                        get("/orders/{id}") {
                            withContext(Dispatchers.IO) {
                                tracing
                                    .getTracer("test")
                                    .spanBuilder("load order")
                                    .startSpan()
                                    .end()
                            }
                            call.respondText("ok")
                        }
                        get("/job") {
                            posthog.withContext(distinctId = "user-9") {
                                tracing
                                    .getTracer("test")
                                    .spanBuilder("nightly job")
                                    .startSpan()
                                    .end()
                            }
                            call.application.log.info("job done")
                            call.respondText("ok")
                        }
                    }
                }
                client.get("/orders/1") {
                    header(DISTINCT_ID_HEADER, "anon-1")
                    header(SESSION_ID_HEADER, "session-1")
                }
                client.get("/job")
            }

            val spans = exporter.finishedSpanItems.associateBy { it.name }
            for (name in listOf("GET /orders/{id}", "load order")) {
                val attributes = spans.getValue(name).attributes
                attributes.get(AttributeKey.stringKey(MDC_DISTINCT_ID)) shouldBe "anon-1"
                attributes.get(AttributeKey.stringKey(MDC_SESSION_ID)) shouldBe "session-1"
            }
            spans.getValue("nightly job").attributes.get(AttributeKey.stringKey(MDC_DISTINCT_ID)) shouldBe "user-9"
        }
    })
