package io.github.slashncode.posthog.ktor

import com.posthog.PostHogEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

private fun event(
    name: String = "search_performed",
    distinctId: String = "user-1",
    sessionId: String? = null,
) = PostHogEvent(
    event = name,
    distinctId = distinctId,
    properties = sessionId?.let { mutableMapOf<String, Any>("\$session_id" to it) } ?: mutableMapOf(),
)

class SamplingTest :
    FunSpec({
        test("by event: only the named events are sampled, at random") {
            sampleByEvent(setOf("search_performed"), 0.0)(event()) shouldBe null
            sampleByEvent(setOf("search_performed"), 1.0)(event())?.event shouldBe "search_performed"
            sampleByEvent(setOf("search_performed"), 0.0)(event("order_placed"))?.event shouldBe "order_placed"
            val kept = (1..10_000).count { sampleByEvent(setOf("search_performed"), 0.3)(event()) != null }
            kept / 10_000.0 shouldBe (0.3 plusOrMinus 0.03)
        }

        test("by distinct id and by session: the same answer every time, about the share asked for") {
            val sample = sampleByDistinctId(0.4)
            val kept = (1..10_000).map { "user-$it" }.filter { id -> sample(event(distinctId = id)) != null }
            kept.size / 10_000.0 shouldBe (0.4 plusOrMinus 0.03)
            kept.all { id -> sample(event(name = "other", distinctId = id)) != null } shouldBe true

            val sessions = sampleBySessionId(0.25)
            val keptSessions = (1..10_000).count { sessions(event(sessionId = "session-$it")) != null }
            keptSessions / 10_000.0 shouldBe (0.25 plusOrMinus 0.03)
            val noSession = event(sessionId = null)
            sessions(noSession) shouldBe noSession
        }

        test("a share outside 0.0 to 1.0 is refused") {
            shouldThrow<IllegalArgumentException> { sampleByDistinctId(1.5) }
            shouldThrow<IllegalArgumentException> { sampleByEvent(setOf("x"), -0.1) }
        }
    })
