# CLAUDE.md

posthog-ktor: a Ktor server integration for PostHog, published to Maven Central as `io.github.slashncode:posthog-ktor`. Standalone open source, meant to be offered to PostHog after 1.0; NothingEats (`../`, workspace root) is its first user. The plan (scope, modules, milestones) is §12 of `../CLAUDE.md`, the reasoning journal record 056.

## Commands

Toolchain from the workspace `mise.toml` (`mise exec -- …`).

- `./gradlew build` — compile, ktlint, API check, tests (JDK 25 toolchain; `-PtestJdk=17|21|25` tests on another JVM)
- `./gradlew ktlintFormat` — format
- `./gradlew updateKotlinAbi` — record an intended public API change in `*/api/*.api`
- `./gradlew publishToMavenLocal` — local publication (unsigned)

## Rules

- Wrap the official `com.posthog:posthog-server`; never reimplement capture, batching or flag evaluation.
- PostHog's vocabulary and option names wherever they exist (tracing headers, `evaluateFlags`, `captureException`, `useTracingHeaders`, `excludedExceptions`, `$current_url`…), so PostHog could adopt the library unchanged.
- Explicit API mode: every public declaration is deliberate, documented (KDoc) and in the `.api` dump. Nothing public without a test.
- Newest stable versions of everything (Renovate); bytecode stays at JVM 17 (`jvm-target` in the version catalog) and compiles against the JDK 17 API.
- Coroutines first: request context lives in the coroutine context, never only in a ThreadLocal.
- Off without a project token: no traffic, no errors, every call a no-op.
- Tests with Kotest `FunSpec`, `testApplication`, and the fake PostHog server for the real SDK.
- Commit and push only when asked; commits use `soeren.poppe@gmx.de` (workspace git config).
