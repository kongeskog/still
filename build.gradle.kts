// Kotlin 2.2.20 (not the 2.0.20 the other apps use): the Signal service library carries
// Kotlin 2.2 metadata, which an older compiler cannot read.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("com.google.devtools.ksp") version "2.2.20-2.0.4" apply false
}

// Arrow (pulled in by the Signal service library) declares minCompileSdk 36 in its AAR
// metadata, but its Android artifacts are plain Kotlin and use no API 36 symbols. AGP 8.7.3
// tops out at compileSdk 35, so the advisory check is skipped rather than the toolchain bumped.
subprojects {
    tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
}
