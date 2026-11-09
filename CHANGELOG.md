# Changelog

## Unreleased

- `install(PostHog)`: a client of PostHog's JVM SDK for the application's lifetime, configured in code or in the `posthog` section of the application config, off without a project token, flushed and closed when the application stops.
- `application.posthog`: capture, identify, alias, group, capture exceptions; the SDK's client for everything else.
- Request context: every call runs with a `PostHogContext` (distinct id from `resolveDistinctId`, else the tracing header; session id; `$current_url`, `$request_method`, `$request_path`, `$route`, `$user_agent`, `$ip`) that survives suspensions and thread switches and is handed to the SDK's own request context. `call.posthog` captures with it; `identify` changes the distinct id for the rest of the call.
- Exceptions of calls that end with a status `captureStatus` accepts (5xx by default) go to error tracking, unless excluded (`excludedExceptions`, `exceptionFilter`); independent of StatusPages and of the order the plugins are installed in.
- `posthogDistinctId` and `sessionId` in the MDC of a call's log lines; `CallLoggingConfig.posthogContext()` for CallLogging's request line.
- Options `useTracingHeaders`, `captureIp`, `captureWithoutDistinctId`, `requestFilter`, `extraProperties`, `propertiesMap`, `logContext`.
- Feature flags: `call.posthog.flags()` evaluates a call's flags once, on first use (with `flagContext` for person properties and groups; again only if `identify` changes the distinct id), `isFeatureEnabled`/`getFeatureFlag` shorthands, `getFlagPayloadAs<T>()` with kotlinx.serialization; events and exceptions of the call carry the flags it read (`attachAccessedFlags`). Without a distinct id every flag is off.
- `featureFlag(key, variant, onDisabled) { … }`: routes that exist only while a flag (or one variant) is on, checked after authentication.
- posthog-js bootstrap without an exposure: the `posthogFlags(path, flagKeys)` route for single-page apps, `call.posthog.bootstrap(flagKeys, mintDistinctId)` with `toJson()` for server-rendered pages (distinct id from the call, posthog-js' cookie, or minted as a UUIDv7).
- Beyond the request: `application.posthog.withContext(distinctId, sessionId, properties) { … }` (or with a `PostHogContext`) for consumers and jobs, bound to the client so the SDK's span processor sees it; `PostHogContext.toHeaders()`/`fromHeaders()` carry the ids through message headers; `snapshot()` detaches a call's context for work that outlives the call.
- New module `posthog-ktor-client`: the `PostHogTracingHeaders` plugin for Ktor's `HttpClient` passes the current distinct id and session id on as tracing headers, to an allowlist of hosts only.
- OpenTelemetry: with posthog-server's `PostHogContextSpanProcessor` registered, the server span and every span inside a call (or `withContext`) carry `posthogDistinctId` and `sessionId`; documented and tested, no module of its own needed.
