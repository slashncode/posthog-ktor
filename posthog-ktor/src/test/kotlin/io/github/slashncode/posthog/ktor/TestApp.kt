package io.github.slashncode.posthog.ktor

import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/**
 * A test application whose PostHog plugin sends to [fake]; [client] runs the requests. The
 * application stops at the end, which flushes what is still queued.
 */
fun postHogTest(
    fake: PostHogFake,
    module: Application.() -> Unit,
    client: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    environment { config = fake.applicationConfig() }
    application(module)
    client()
}
