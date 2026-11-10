plugins {
    id("posthog-ktor.docs")
}

dependencies {
    dokka(project(":posthog-ktor"))
    dokka(project(":posthog-ktor-client"))
    dokka(project(":posthog-ktor-test"))
    dokka(project(":posthog-ktor-opentelemetry"))
    dokka(project(":posthog-ktor-langchain4j"))
}
