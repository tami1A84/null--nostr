plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }

    // iOS targets can be uncommented when building for iOS:
    // iosArm64()
    // iosSimulatorArm64()
    // iosX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

android {
    namespace = "io.nurunuru.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
