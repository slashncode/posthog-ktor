# Module posthog-ktor

The Ktor server plugin: `install(PostHog)`, the request context (`call.posthog`, `PostHogContext`), exception capture, feature flags (`call.posthog.flags()`, `featureFlag { }`), posthog-js bootstrap (`posthogFlags`, `call.posthog.bootstrap`), the `/ingest` reverse proxy (`posthogProxy`) and context for work outside a call (`application.posthog.withContext`).
