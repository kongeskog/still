import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.kongeskog.still.bridge"
    compileSdk = 35
    buildToolsVersion = "34.0.0"
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

// Room writes each schema version here; migrations copy their CREATE statements from it.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// These three move together: the service library pins one libsignal version, and the
// Android native library must be that same version. Bump them when signal-cli releases.
val signalNetwork = "2.15.3_unofficial_157"
val libsignal = "0.106.1"

dependencies {
    // The Signal service library behind signal-cli (GPLv3). Kept as `implementation`
    // so no org.whispersystems type leaks into the app or UI modules.
    implementation("com.github.turasa:signal-network:$signalNetwork") {
        exclude("junit", "junit")
        exclude("com.willowtreeapps.assertk")
    }
    // Adds the Android .so; the Java classes come from libsignal-client, which stays.
    implementation("org.signal:libsignal-android:$libsignal")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    // Signal's calling engine (WebRTC inside). Voice calls only are wired up.
    implementation("org.signal:ringrtc-android:2.75.0")
    testImplementation("junit:junit:4.13.2")
}
