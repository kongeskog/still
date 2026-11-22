import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kongeskog.still.ui"
    compileSdk = 35
    buildToolsVersion = "34.0.0"
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    api("com.mudita:MMD:1.0.2")
    api("androidx.activity:activity-compose:1.10.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.compose.material:material-icons-extended:1.7.3")
}
