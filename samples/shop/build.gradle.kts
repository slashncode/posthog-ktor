plugins {
    id("posthog-ktor.sample")
}

application {
    mainClass.set("io.github.slashncode.posthog.ktor.sample.ShopKt")
}

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(project(":posthog-ktor"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback.classic)

    testImplementation(project(":posthog-ktor-test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}
