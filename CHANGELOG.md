# Changelog

## Unreleased

- `install(PostHog)`: a client of PostHog's JVM SDK for the application's lifetime, configured in code or in the `posthog` section of the application config, off without a project token, flushed and closed when the application stops.
- `application.posthog`: capture events; the SDK's client for everything else.
