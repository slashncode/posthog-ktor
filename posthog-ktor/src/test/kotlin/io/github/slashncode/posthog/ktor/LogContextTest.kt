package io.github.slashncode.posthog.ktor

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class LogContextTest :
    FunSpec({
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        beforeEach { appender.list.clear() }
        beforeSpec { root.addAppender(appender) }
        afterSpec { root.detachAppender(appender) }

        fun lines(message: String) = appender.list.filter { it.formattedMessage.contains(message) }

        test("log lines of a call, on any thread, and CallLogging's request line carry the PostHog ids") {
            FakePostHog().use { fake ->
                postHogTest(fake, {
                    install(CallLogging) {
                        posthogContext()
                        disableDefaultColors()
                    }
                    install(PostHog)
                    routing {
                        get("/logged") {
                            val log = LoggerFactory.getLogger("test.handler")
                            log.info("in the handler")
                            withContext(Dispatchers.IO) { log.info("on another thread") }
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/logged") {
                        header(DISTINCT_ID_HEADER, "anon-5")
                        header(SESSION_ID_HEADER, "session-5")
                    }
                }
            }

            listOf("in the handler", "on another thread", "GET - /logged").forEach { message ->
                val line = lines(message).single()
                line.mdcPropertyMap[MDC_DISTINCT_ID] shouldBe "anon-5"
                line.mdcPropertyMap[MDC_SESSION_ID] shouldBe "session-5"
            }
            MDC.get(MDC_DISTINCT_ID) shouldBe null
        }

        test("the log context can be turned off") {
            FakePostHog().use { fake ->
                postHogTest(fake, {
                    install(PostHog) { logContext = false }
                    routing {
                        get("/quiet") {
                            LoggerFactory.getLogger("test.handler").info("without ids")
                            call.respondText("ok")
                        }
                    }
                }) {
                    client.get("/quiet") { header(DISTINCT_ID_HEADER, "anon-6") }
                }
            }

            lines("without ids")
                .single()
                .mdcPropertyMap.keys
                .filter { it == MDC_DISTINCT_ID }
                .shouldBeEmpty()
        }
    })
