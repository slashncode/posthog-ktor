// The API reference of every published module in one site (`./gradlew :dokkaGenerate`, written to
// build/dokka/html), deployed to GitHub Pages by the docs workflow.

plugins {
    id("org.jetbrains.dokka")
}

dokka {
    moduleName.set("posthog-ktor")
}
