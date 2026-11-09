plugins {
    id("posthog-ktor.library")
}

description = "Ktor server plugin for PostHog: product analytics, feature flags and error tracking in Ktor apps"

dependencies {
    api(platform(libs.ktor.bom))
    api(libs.ktor.server.core)
    api(libs.posthog.server)
    // Flag payloads decode with it; the bootstrap is written with it.
    api(libs.kotlinx.serialization.json)
    // Only for CallLoggingConfig.posthogContext(); apps without CallLogging never load it.
    compileOnly(libs.ktor.server.call.logging)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.call.logging)
    testImplementation(libs.ktor.server.status.pages)
    testImplementation(libs.ktor.server.auth)
    testImplementation(libs.ktor.server.forwarded.header)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.logback.classic)
    testImplementation(platform(libs.opentelemetry.bom))
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.opentelemetry.ktor)
}
