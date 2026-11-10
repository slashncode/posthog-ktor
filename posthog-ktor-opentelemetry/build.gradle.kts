plugins {
    id("posthog-ktor.library")
}

description = "OpenTelemetry for PostHog in Ktor apps: traces, logs and AI spans to PostHog, linked to persons and sessions"

dependencies {
    api(platform(libs.ktor.bom))
    api(platform(libs.opentelemetry.bom))
    api(project(":posthog-ktor"))
    api(libs.opentelemetry.sdk)
    api(libs.opentelemetry.extension.kotlin)
    // KtorServerTelemetry and KtorClientTelemetry.
    api(libs.opentelemetry.ktor)
    // OTLP over the JDK's HTTP client instead of OkHttp: no HTTP library added to the app.
    implementation(libs.opentelemetry.exporter.otlp) {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    implementation(libs.opentelemetry.exporter.sender.jdk)
    implementation(libs.opentelemetry.logback.appender)
    // Logs go through Logback, Ktor's usual logger; apps with another SLF4J backend skip them.
    compileOnly(libs.logback.classic)
    // Only for HttpClientConfig.posthogOpenTelemetry().
    compileOnly(libs.ktor.client.core)

    testImplementation(project(":posthog-ktor-test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.logback.classic)
    // Koog's agents export through OpenTelemetry: tested with a real agent and a scripted model.
    testImplementation(libs.koog.agents)
    testImplementation(libs.koog.agents.test)
}
