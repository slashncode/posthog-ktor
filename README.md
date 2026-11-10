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
| **Feature flags** | One evaluation per request, payloads decoded with kotlinx.serialization, routes gated by a flag (`featureFlag("new-checkout") { … }`), the flags a request read attached to its events | ✓ |
| **Bootstrapping posthog-js** | A route that hands a single-page app its flags before the first render, and the same for server-rendered pages, without counting an exposure | ✓ |
| **Reverse proxy** | `/ingest` forwarded to PostHog, so ad blockers do not drop events | ✓ |
| **Beyond the request** | The context for background jobs and message consumers, through message headers; a Ktor `HttpClient` plugin passing it on to other services (`posthog-ktor-client`) | ✓ |
| **The SDKs' API** | What PostHog's own SDKs offer, for a server: `beforeSend` hooks and sampling, super properties, person properties (`setPersonProperties`, `unset…`), person profiles (`IDENTIFIED_ONLY`), groups for a call or an event, opting out at runtime, GeoIP from the visitor's address only, flag results with payloads, flags outside calls, overriding person and group properties for flags, enriched flag analytics, pageviews | ✓ |
| **AI observability** | Generations, embeddings, spans and traces (`call.posthog.ai`), LangChain4j's chat models (`posthog-ktor-langchain4j`), and Koog or any OpenTelemetry `gen_ai` instrumentation, for the call's person and session, with privacy mode | ✓ |
| **OpenTelemetry** | Traces, Logback logs and AI spans to PostHog without a collector, every span and log line with the call's person and session (`posthog-ktor-opentelemetry`) | ✓ |
| **Tests** | A fake PostHog server for the real client, with scripted flags and assertions (`assertCaptured("order_placed") { property("total", 12) }`), in `posthog-ktor-test` | ✓ |

## Documentation

- **[Guide](https://slashncode.github.io/posthog-ktor/)**: installation, configuration, capturing events, identifying users, feature flags, experiments, error tracking, logs, traces, background work, the reverse proxy, testing and every option.
- **[API reference](https://slashncode.github.io/posthog-ktor/api/)**: every public declaration (Dokka).

## Quickstart

```kotlin
dependencies {
    implementation("io.github.slashncode:posthog-ktor:<version>")
}
```

```kotlin
fun Application.module() {
    install(Authentication) { /* … */ }
    install(PostHog) {
        projectToken = "<ph_project_token>"
        host = "https://us.i.posthog.com"
        // The signed-in user, where there is one; else the browser's id from posthog-js' tracing headers.
        resolveDistinctId { it.principal<UserIdPrincipal>()?.name }
    }

    routing {
        authenticate {
            post("/orders") {
                // …
                call.posthog.capture("order_placed", mapOf("items" to 2))
            }
        }
        featureFlag("new-checkout") {
            post("/checkout") { /* exists only for callers the flag is on for */ }
        }
    }
}
```

Events captured during a call carry the call's distinct id, session id and request properties, also from other threads. Exceptions that end a call with a 5xx go to error tracking. Without a project token, PostHog stays off and every call is a no-op.

## Sample

[`samples/shop`](samples/shop) is a one-page general store with PostHog on both sides: posthog-js through the app's `/ingest` proxy, bootstrapped with the server's flags, a server-rendered experiment, a route behind a flag, and tests on `PostHogFake`. `POSTHOG_PROJECT_TOKEN=phc_… ./gradlew :samples:shop:run` serves it on port 8080; without a token it runs with PostHog off.

## Development

- `./gradlew build` builds and tests everything (JDK 25 from the Gradle toolchain); `-PtestJdk=17` runs the tests on another JVM.
- The public API is recorded in `*/api/*.api`; after an intended change, `./gradlew updateKotlinAbi`.
- `./gradlew ktlintFormat` formats; `./gradlew :dokkaGenerate` writes the API reference to `build/dokka/html`.
- The guide is an Astro Starlight site in `docs/`: `pnpm install` and `pnpm dev` there (Node 24, pnpm 12). The `docs` workflow publishes it with the API reference under `/api/` from `main`.
- Releases: a tag `vX.Y.Z` publishes to Maven Central; a manual run of the `release` workflow uploads a version for validation only.

Requires Java 17 or newer and Ktor 3.

## License

MIT
