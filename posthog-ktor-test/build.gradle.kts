plugins {
    id("posthog-ktor.library")
}

description = "Test support for posthog-ktor: a fake PostHog server for the real client, with assertions on what was captured"

dependencies {
    api(platform(libs.ktor.bom))
    api(project(":posthog-ktor"))

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.logback.classic)
}
