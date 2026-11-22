pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // libsignal releases after 0.86.5 are published here only, not on Maven Central.
        maven("https://build-artifacts.signal.org/libraries/maven/") {
            content { includeGroupByRegex("org\\.signal.*") }
        }
    }
}
rootProject.name = "still"
include(":app", ":still-ui", ":signal-bridge")
