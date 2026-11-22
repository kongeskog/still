import java.util.Properties

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kongeskog.still"
    compileSdk = 35
    buildToolsVersion = "34.0.0"
    // Must name the installed NDK, or AGP cannot strip libsignal's 124 MB of debug symbols.
    ndkVersion = "27.1.12297006"
    defaultConfig {
        applicationId = "com.kongeskog.still"
        minSdk = 31
        targetSdk = 31 // the Kompakt runs Android 12
        versionCode = 4
        versionName = "0.1.2"
        // The phone is arm64. `-Pemulator` adds x86_64 for the desktop emulator only.
        ndk { abiFilters += if (project.hasProperty("emulator")) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a") }
    }
    // The release key and its password stay out of the repository. Put `release.keystore` in the
    // project root and `still.keystore.password=...` in local.properties; without them a release
    // build is signed with the debug key, which is fine for trying the app out.
    val keystore = rootProject.file("release.keystore")
    val keystorePassword: String? = rootProject.file("local.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) }.getProperty("still.keystore.password") }
    signingConfigs {
        if (keystore.exists() && keystorePassword != null) {
            create("local") {
                storeFile = keystore
                storePassword = keystorePassword
                keyAlias = "still"
                keyPassword = keystorePassword
            }
        }
    }
    buildTypes {
        debug {
            // Its own package, so a debug build never collides with, or touches the data of, the real app.
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 matters here: unminified Compose is noticeably slow on this hardware.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("local") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        jniLibs { excludes += "**/libsignal_jni_testing.so" }
        // libsignal-client ships desktop natives and .proto sources at the jar root.
        resources {
            excludes += setOf(
                "libsignal_jni*.so", "**/*.dylib", "**/*.dll", "**/*.proto",
                "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/{AL2.0,LGPL2.1,LICENSE*,NOTICE*,DEPENDENCIES}",
            )
        }
    }
    lint { disable += "ExpiredTargetSdkVersion" }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(project(":still-ui"))
    implementation(project(":signal-bridge"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

