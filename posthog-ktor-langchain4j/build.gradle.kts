plugins {
    id("posthog-ktor.library")
}

description = "LangChain4j for PostHog in Ktor apps: every chat model call as a PostHog AI generation"

dependencies {
    api(platform(libs.ktor.bom))
    api(project(":posthog-ktor"))
    api(libs.langchain4j.core)

    testImplementation(project(":posthog-ktor-test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.logback.classic)
}
