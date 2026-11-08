# posthog-ktor

PostHog for [Ktor](https://ktor.io) servers: product analytics, feature flags, experiments and error tracking that know which request, person and session they belong to.

> **Status: in development, nothing released yet.** The plan below is what 1.0 ships. Follow the [releases](https://github.com/slashncode/posthog-ktor/releases) for the first version on Maven Central.

PostHog has integrations for Django, Rails, Laravel, ASP.NET Core, Express and Next.js. Ktor developers get the JVM server SDK and a request context kept in a `ThreadLocal`, which a coroutine loses when it resumes on another thread. posthog-ktor builds on that official SDK (`com.posthog:posthog-server`: batching, retries, local flag evaluation stay PostHog's) and adds what the framework integrations offer, the Ktor way.

## What 1.0 brings

| | | |
|---|---|---|
| **Setup** | `install(PostHog)` from code or the `posthog` section of `application.conf`/YAML; no project token, no traffic (local development, tests); the client is flushed and closed when the application stops | ✓ |
| **Request context** | The browser's distinct id and session id (tracing headers `X-PostHog-Distinct-Id`, `X-PostHog-Session-Id`) or the user from Ktor's authentication, kept in the coroutine context across suspensions and threads, and handed to the SDK so its own calls see it; request properties (`$current_url`, `$request_method`, `$request_path`, `$route`, `$user_agent`, `$ip`) on every event | ✓ |
| **Error tracking** | Exceptions of failed calls to PostHog error tracking, by the status the call ends with (5xx by default, whether StatusPages answered or not), with an exclusion list and a filter | ✓ |
| **Logs** | The PostHog ids in the MDC of every log line of a call, CallLogging's request line included, for PostHog Logs | ✓ |
| **Feature flags** | One evaluation per request, payloads decoded with kotlinx.serialization, routes gated by a flag (`featureFlag("new-checkout") { … }`), flags attached to the request's events | |
| **Single-page apps** | A route that hands the browser its flags before the first render, without counting an exposure, to bootstrap posthog-js | |
| **Reverse proxy** | `/ingest` forwarded to PostHog, so ad blockers do not drop events | |
| **Traces** | The PostHog ids on the OpenTelemetry server span | |
| **Beyond the request** | The context for background jobs and message consumers; a Ktor `HttpClient` plugin passing it on to other services | |
| **Tests** | A fake client with assertions (`assertCaptured("order_placed")`) and a fake PostHog server | |

## Usage

```kotlin
dependencies {
    implementation("io.github.slashncode:posthog-ktor:<version>")
}
```

```kotlin
fun Application.module() {
    install(Authentication) { /* … */ }
    install(PostHog) {
        projectToken = "phc_…"
        host = "https://eu.i.posthog.com"
        // The signed-in user, where there is one; else the browser's id from posthog-js' tracing headers.
        resolveDistinctId { it.principal<UserIdPrincipal>()?.name }
    }
    install(CallLogging) { posthogContext() }

    routing {
        authenticate {
            post("/orders") {
                // …
                call.posthog.capture("order_placed", mapOf("items" to 2))
            }
        }
    }
}
```

Events captured during a call carry the call's distinct id, session id and request properties, also from other threads (`withContext(Dispatchers.IO)`), from `application.posthog` and from direct calls of the official SDK (`application.posthog.sdk`). Exceptions that end a call with a 5xx go to error tracking.

| Option | Default | |
|---|---|---|
| `projectToken`, `host`, `secretKey` | –, US cloud, – | `secretKey` (Feature Flags Secure API Key or personal API key) turns on local flag evaluation |
| `useTracingHeaders` | `true` | Ids from posthog-js' tracing headers: analytics context, never authentication |
| `resolveDistinctId { call -> }` | – | The authenticated user's id; wins over the header |
| `captureWithoutDistinctId` | `true` | Off: events without any id are dropped instead of sent without a person (with posthog-js sending its ids only after consent, nothing about visitors who declined) |
| `captureIp` | `true` | `$ip` from the client address, honouring Ktor's forwarded-headers plugins |
| `requestFilter`, `extraProperties`, `propertiesMap` | – | Skip calls (health checks), add properties, change or drop request properties |
| `captureExceptions`, `captureStatus`, `excludedExceptions`, `exceptionFilter` | `true`, 5xx, client errors and gone clients, – | What a failed call reports |
| `logContext` | `true` | `posthogDistinctId` and `sessionId` in the MDC |
| `sdk { }` | – | Every other option of `posthog-server` |

## Development

- `./gradlew build` builds and tests everything (JDK 25 from the Gradle toolchain); `-PtestJdk=17` runs the tests on another JVM.
- The public API is recorded in `*/api/*.api`; after an intended change, `./gradlew updateKotlinAbi`.
- `./gradlew ktlintFormat` formats.
- Releases: a tag `vX.Y.Z` publishes to Maven Central; a manual run of the `release` workflow uploads a version for validation only.

Requires Java 17 or newer and Ktor 3.

## License

MIT
