plugins {
    id("posthog-ktor.library")
}

description = "Ktor client plugin for PostHog: passes the current distinct id and session id on to the services an app calls"

dependencies {
    api(platform(libs.ktor.bom))
    api(project(":posthog-ktor"))
    api(libs.ktor.client.core)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.logback.classic)
}
