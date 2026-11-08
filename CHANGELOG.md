# Changelog

## Unreleased

- `install(PostHog)`: a client of PostHog's JVM SDK for the application's lifetime, configured in code or in the `posthog` section of the application config, off without a project token, flushed and closed when the application stops.
- `application.posthog`: capture, identify, alias, group, capture exceptions; the SDK's client for everything else.
- Request context: every call runs with a `PostHogContext` (distinct id from `resolveDistinctId`, else the tracing header; session id; `$current_url`, `$request_method`, `$request_path`, `$route`, `$user_agent`, `$ip`) that survives suspensions and thread switches and is handed to the SDK's own request context. `call.posthog` captures with it; `identify` changes the distinct id for the rest of the call.
- Exceptions of calls that end with a status `captureStatus` accepts (5xx by default) go to error tracking, unless excluded (`excludedExceptions`, `exceptionFilter`); independent of StatusPages and of the order the plugins are installed in.
- `posthogDistinctId` and `sessionId` in the MDC of a call's log lines; `CallLoggingConfig.posthogContext()` for CallLogging's request line.
- Options `useTracingHeaders`, `captureIp`, `captureWithoutDistinctId`, `requestFilter`, `extraProperties`, `propertiesMap`, `logContext`.
