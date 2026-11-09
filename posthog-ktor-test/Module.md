# Module posthog-ktor-test

`PostHogFake`, a local fake PostHog for tests of an app with the real client: it keeps what the app captured, answers flag evaluations from flags the test sets, and asserts on events (`assertCaptured("order_placed") { property("total", 12) }`).
