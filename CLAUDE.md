# CLAUDE.md

posthog-ktor: a Ktor server integration for PostHog, published to Maven Central as `io.github.slashncode:posthog-ktor`. Standalone open source for general use. Releases stay at 0.x (0.9 at most) until PostHog has reviewed or adopted the library and real users have run it; that is 1.0. PostHog is offered it before then.

## Commands

Gradle provisions JDK 25 through its toolchains; the guide in `docs/` needs Node 24 and pnpm 12.

- `./gradlew build` — compile, ktlint, API check, tests (JDK 25 toolchain; `-PtestJdk=17|21|25` tests on another JVM)
- `./gradlew ktlintFormat` — format
- `./gradlew updateKotlinAbi` — record an intended public API change in `*/api/*.api`
- `./gradlew publishToMavenLocal` — local publication (unsigned)
- `./gradlew :dokkaGenerate` — API reference of all modules in `build/dokka/html` (deployed to GitHub Pages from `main` by `docs.yml`)
- `pnpm dev` / `pnpm build` in `docs/` — the guide (Astro Starlight); the `docs` workflow publishes it to GitHub Pages with the API reference under `/api/`
- `./gradlew :samples:shop:run` — the sample app on port 8080 (`POSTHOG_PROJECT_TOKEN` to turn PostHog on)

## Layout

- `posthog-ktor` — the server plugin (with AI observability); `posthog-ktor-client` — the `HttpClient` plugin; `posthog-ktor-opentelemetry` — traces, logs and AI spans to PostHog over OTLP; `posthog-ktor-langchain4j` — LangChain4j chat models as AI generations; `posthog-ktor-test` — `PostHogFake` (events, flags, OTLP; the library's own tests run on it); `samples/shop` — a general store, not published; `docs` — the guide.
- `build-logic` — convention plugins: `posthog-ktor.library` (published modules), `posthog-ktor.sample`, `posthog-ktor.docs` (root).

## Rules

- Wrap the official `com.posthog:posthog-server`; never reimplement capture, batching or flag evaluation.
- PostHog's vocabulary and option names wherever they exist (tracing headers, `evaluateFlags`, `captureException`, `useTracingHeaders`, `excludedExceptions`, `$current_url`…), so PostHog could adopt the library unchanged.
- Explicit API mode: every public declaration is deliberate, documented (KDoc) and in the `.api` dump. Nothing public without a test.
- Newest stable versions of everything (Renovate); bytecode stays at JVM 17 (`jvm-target` in the version catalog) and compiles against the JDK 17 API.
- Coroutines first: request context lives in the coroutine context, never only in a ThreadLocal.
- Off without a project token: no traffic, no errors, every call a no-op.
- Tests with Kotest `FunSpec`, `testApplication`, and the fake PostHog server for the real SDK.
- Examples, tests, samples and docs are generic (a general store: `new-checkout`, `checkout-button` with variant `buy-now`, `order_placed`), never tied to an app that uses the library or to its maintainer.
- The guide (`docs/src/content/docs/index.mdx`) follows the chapters of PostHog's library docs; keep it in step with the public API.
- Commit and push only when asked.
