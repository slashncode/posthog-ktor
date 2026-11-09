import org.gradle.api.artifacts.VersionCatalogsExtension

// Conventions for the sample apps: runnable Kotlin applications on the build's JDK, checked by
// ktlint and tested, never published.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    application
}

val versions = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(versions.findVersion("java").get().requiredVersion.toInt())
    compilerOptions { allWarningsAsErrors.set(true) }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
