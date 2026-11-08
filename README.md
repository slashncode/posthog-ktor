# posthog-ktor

PostHog for [Ktor](https://ktor.io) servers: product analytics, feature flags, experiments and error tracking that know which request, person and session they belong to.

> **Status: in development, nothing released yet.** The plan below is what 1.0 ships. Follow the [releases](https://github.com/slashncode/posthog-ktor/releases) for the first version on Maven Central.

PostHog has integrations for Django, Rails, Laravel, ASP.NET Core, Express and Next.js. Ktor developers get the JVM server SDK and a request context kept in a `ThreadLocal`, which a coroutine loses when it resumes on another thread. posthog-ktor builds on that official SDK (`com.posthog:posthog-server`: batching, retries, local flag evaluation stay PostHog's) and adds what the framework integrations offer, the Ktor way.

## Planned for 1.0

| | |
|---|---|
| **Setup** | `install(PostHog)` from code or the `posthog` section of `application.conf`/YAML; no project token, no traffic (local development, tests); the client is flushed and closed when the application stops |
| **Request context** | The browser's PostHog distinct id and session id (tracing headers `X-PostHog-Distinct-Id`, `X-PostHog-Session-Id`) or the user from Ktor's authentication, kept in the coroutine context; request properties (`$current_url`, `$request_method`, `$request_path`, `$route`, `$user_agent`, `$ip`) on every event |
| **Error tracking** | Exceptions of a call to PostHog error tracking, server errors only by default, with an exclusion list |
| **Feature flags** | One evaluation per request, payloads decoded with kotlinx.serialization, routes gated by a flag (`featureFlag("new-checkout") { … }`), flags attached to the request's events |
| **Single-page apps** | A route that hands the browser its flags before the first render, without counting an exposure, to bootstrap posthog-js |
| **Reverse proxy** | `/ingest` forwarded to PostHog, so ad blockers do not drop events |
| **Logs and traces** | The PostHog ids in the MDC of every log line of a call and on the OpenTelemetry server span, linking logs and traces to persons and session replays |
| **Beyond the request** | The context for background jobs and message consumers; a Ktor `HttpClient` plugin passing it on to other services |
| **Tests** | A fake client with assertions (`assertCaptured("order_placed")`) and a fake PostHog server |

## Usage today

```kotlin
dependencies {
    implementation("io.github.slashncode:posthog-ktor:<version>")
}
```

```kotlin
fun Application.module() {
    install(PostHog) {
        projectToken = "phc_…"
        host = "https://eu.i.posthog.com"
    }

    routing {
        post("/orders") {
            // …
            application.posthog.capture("order_placed", distinctId = userId, mapOf("items" to 2))
        }
    }
}
```

## Development

- `./gradlew build` builds and tests everything (JDK 25 from the Gradle toolchain); `-PtestJdk=17` runs the tests on another JVM.
- The public API is recorded in `*/api/*.api`; after an intended change, `./gradlew apiDump`.
- `./gradlew ktlintFormat` formats.
- Releases: a tag `vX.Y.Z` publishes to Maven Central; a manual run of the `release` workflow uploads a version for validation only.

Requires Java 17 or newer and Ktor 3.

## License

MIT
