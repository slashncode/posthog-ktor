import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Conventions for every published module: Kotlin with explicit API, built on the newest JDK but
// compiled for the oldest one users may run, ktlint, Dokka, and publishing to Maven Central.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
    id("org.jlleitschuh.gradle.ktlint")
}

val versions = extensions.getByType<VersionCatalogsExtension>().named("libs")
val buildJdk = versions.findVersion("java").get().requiredVersion.toInt()
val targetJvm = versions.findVersion("jvm-target").get().requiredVersion

kotlin {
    explicitApi()
    jvmToolchain(buildJdk)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(targetJvm))
        // Compile against the target's JDK API, not the build JDK's, so nothing newer slips in.
        freeCompilerArgs.addAll("-Xjdk-release=$targetJvm")
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(targetJvm.toInt())
}

// `-PtestJdk=17` runs the tests on another JVM than the build's (CI tests every supported one).
val testJdk = providers.gradleProperty("testJdk").map(String::toInt)
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    if (testJdk.isPresent) {
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(testJdk.get())) })
    }
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), project.name, version.toString())
    pom {
        name.set(project.name)
        description.set(provider { project.description })
        url.set("https://github.com/slashncode/posthog-ktor")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("slashncode")
                name.set("Sören Poppe")
                url.set("https://github.com/slashncode")
            }
        }
        scm {
            url.set("https://github.com/slashncode/posthog-ktor")
            connection.set("scm:git:https://github.com/slashncode/posthog-ktor.git")
            developerConnection.set("scm:git:ssh://git@github.com/slashncode/posthog-ktor.git")
        }
    }
}
