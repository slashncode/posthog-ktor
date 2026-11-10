pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "posthog-ktor-root"

include(":posthog-ktor")
include(":posthog-ktor-client")
include(":posthog-ktor-test")
include(":posthog-ktor-opentelemetry")
include(":posthog-ktor-langchain4j")
include(":samples:shop")
