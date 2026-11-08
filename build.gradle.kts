plugins {
    alias(libs.plugins.binary.compatibility.validator)
}

// The public API of every module is recorded in `<module>/api/*.api`: `./gradlew apiDump` after an
// intended change, `apiCheck` (part of `check`) fails on an unintended one.
apiValidation {
    // Samples are not published.
    ignoredProjects += listOf<String>()
}
