# Module posthog-ktor-opentelemetry

`PostHogOpenTelemetry`: an OpenTelemetry SDK that sends to the app's PostHog project without a collector. Traces go to PostHog's tracing with the call's person and session on every span, Logback's log lines to PostHog Logs, and the AI spans of model libraries (Koog, Spring AI) to AI observability. `PostHogAiSpanProcessor` and `PostHogAiSpanExporter` add PostHog's AI observability to an OpenTelemetry SDK built elsewhere; `Tracer.withSpan` and `HttpClientConfig.posthogOpenTelemetry` trace your own work and the services you call.
