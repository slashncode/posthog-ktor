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
| **Traces** | The PostHog ids on the OpenTelemetry server span and every span inside a call, through the official SDK's span processor | ✓ |
| **Beyond the request** | The context for background jobs and message consumers, through message headers; a Ktor `HttpClient` plugin passing it on to other services (`posthog-ktor-client`) | ✓ |
| **Tests** | A fake PostHog server for the real client, with scripted flags and assertions (`assertCaptured("order_placed") { property("total", 12) }`), in `posthog-ktor-test` | ✓ |

## Usage

```kotlin
dependencies {
    implementation("io.github.slashncode:posthog-ktor:<version>")
    // Optional: passes the PostHog ids on to services called with Ktor's HttpClient.
    implementation("io.github.slashncode:posthog-ktor-client:<version>")
    testImplementation("io.github.slashncode:posthog-ktor-test:<version>")
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

### Feature flags

```kotlin
routing {
    // Exists only while the flag is on for the caller (404 otherwise, or onDisabled).
    featureFlag("new-checkout") {
        post("/checkout") { /* … */ }
    }

    get("/wheel") {
        val flags = call.posthog.flags() // evaluated once per call, on first use
        val odds = flags.getFlagPayloadAs<Odds>("spin-wheel-odds") // a @Serializable class; no exposure
        if (flags.getFlag("spin-wheel-odds") == "generous") { /* … */ } // records the exposure
        call.posthog.capture("wheel_spun") // carries $feature/spin-wheel-odds
    }

    // posthog-js' bootstrap for a single-page app: values and payloads of these flags, no exposure.
    posthogFlags("/api/flags", listOf("checkout-copy", "deal-countdown"))
}
```

The single-page app fetches the bootstrap before its first render and starts with it, so experiments show their variant without flicker; posthog-js records the exposure when the page reads the flag. A same-origin request carries posthog-js' cookie (`ph_<projectToken>_posthog`), which names a returning visitor; a first visit gets no flags and posthog-js asks PostHog itself:

```ts
const bootstrap = await fetch('/api/flags').then(r => r.json())
posthog.init('phc_…', { api_host: '/ingest', bootstrap })
```

A server-rendered page embeds `call.posthog.bootstrap(listOf("checkout-copy")).toJson()` in its `posthog.init` script (escaped for `<script>`); with `mintDistinctId = true` a first-time visitor gets a new anonymous id that posthog-js goes on with.

Flags are evaluated for the call's distinct id; without one (no consent, no tracing headers) every flag is off and PostHog is not asked. With a `secretKey` they are evaluated locally, without a request per call.

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
| `flagContext { call -> }` | – | Person properties, groups and group properties for flag evaluation (`PostHogEvaluateFlagsOptions.Builder`) |
| `attachAccessedFlags` | `true` | Events of a call carry the flags it read (`$feature/<key>`, `$active_feature_flags`) |
| `flushAt`, `flushIntervalSeconds` | SDK defaults | When queued events are sent |
| `sdk { }` | – | Every other option of `posthog-server` |

### Reverse proxy

```kotlin
routing { posthogProxy("/ingest") }
```

```ts
posthog.init('phc_…', { api_host: '/ingest', ui_host: 'https://eu.posthog.com' })
```

posthog-js then talks to the app's own origin, which ad blockers leave alone. Scripts (`/ingest/static/…`) come from PostHog's assets host, everything else goes to the plugin's `host`; bodies stream through compressed as they came. The visitor's cookies and `Authorization` stay behind, PostHog's `Set-Cookie` does not come back, and PostHog gets the visitor's address in `X-Forwarded-For` for GeoIP.

### Beyond the request

```kotlin
// A consumer or job: everything captured inside belongs to this person, on any thread.
application.posthog.withContext(distinctId = order.customerId) {
    application.posthog.capture("order_delivered")
}

// Through a queue, e.g. Kafka: the ids travel as headers and come back as a context.
val record = ProducerRecord(topic, key, value)
PostHogContext.current()?.toHeaders()?.forEach { (name, id) -> record.headers().add(name, id.toByteArray()) }
// … and in the consumer:
val headers = record.headers().associate { it.key() to it.value().decodeToString() }
application.posthog.withContext(PostHogContext.fromHeaders(headers) ?: PostHogContext()) { handle(record) }

// Work that outlives the call: a snapshot of the call's context.
application.launch(PostHogContext.current()!!.snapshot()) { /* … */ }
```

Calls to other services carry the ids with the client plugin from `io.github.slashncode:posthog-ktor-client`, only to the hosts it names:

```kotlin
val orders = HttpClient(CIO) {
    install(PostHogTracingHeaders) { hosts += listOf("orders.internal", "*.svc.cluster.local") }
}
```

### Traces

posthog-server ships `PostHogContextSpanProcessor`, which writes `posthogDistinctId` and `sessionId` on spans. posthog-ktor hands the SDK each call's context whenever the call's coroutine runs, so with the processor registered for the application's client, the server span of OpenTelemetry's Ktor instrumentation and every span started inside the call carry the ids:

```kotlin
install(PostHog) { /* … */ }
val tracing = OpenTelemetrySdk.builder()
    .setTracerProvider(
        SdkTracerProvider.builder()
            .apply { posthog.sdk?.let { addSpanProcessor(PostHogContextSpanProcessor(it)) } }
            .addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.getDefault()).build())
            .build(),
    ).build()
install(KtorServerTelemetry) { setOpenTelemetry(tracing) }
```

Outside a call, `application.posthog.withContext { }` binds the context to the client, which the processor needs.

### Testing

`posthog-ktor-test` runs the real client against a local fake PostHog: what the app captures arrives there, flags come from the test.

```kotlin
PostHogFake().use { posthog ->
    posthog.flag("new-checkout", true)
    testApplication {
        environment { config = posthog.applicationConfig() }
        application { module() }

        client.post("/checkout") { header(DISTINCT_ID_HEADER, "anon-1") }

        posthog.assertCaptured("order_placed") {
            property("total", 12)
            flags shouldBe mapOf("new-checkout" to true)
        }
        posthog.assertNotCaptured("order_failed")
    }
}
```

## Sample

[`samples/shop`](samples/shop) is a one-page shop with PostHog on both sides: posthog-js through the app's `/ingest` proxy, bootstrapped with the server's flags, a server-rendered experiment, a route behind a flag, and tests on `PostHogFake`. `POSTHOG_PROJECT_TOKEN=phc_… ./gradlew :samples:shop:run` serves it on port 8080; without a token it runs with PostHog off.

## API reference

[slashncode.github.io/posthog-ktor](https://slashncode.github.io/posthog-ktor/) (Dokka, from `main`).

## Development

- `./gradlew build` builds and tests everything (JDK 25 from the Gradle toolchain); `-PtestJdk=17` runs the tests on another JVM.
- The public API is recorded in `*/api/*.api`; after an intended change, `./gradlew updateKotlinAbi`.
- `./gradlew ktlintFormat` formats; `./gradlew :dokkaGenerate` writes the API reference to `build/dokka/html`.
- Releases: a tag `vX.Y.Z` publishes to Maven Central; a manual run of the `release` workflow uploads a version for validation only.

Requires Java 17 or newer and Ktor 3.

## License

MIT
