package io.github.slashncode.posthog.ktor

import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/**
 * A test application whose PostHog plugin sends to [fake]; [client] runs the requests. The
 * application stops at the end, which flushes the events to [fake].
 */
fun postHogTest(
    fake: FakePostHog,
    module: Application.() -> Unit,
    client: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    environment {
        config = MapApplicationConfig("posthog.projectToken" to "phc_test", "posthog.host" to fake.url)
    }
    application(module)
    client()
}
