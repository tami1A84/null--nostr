// nurunuru-ffi Android library module
//
// Setup:
//   1. Build native libraries and Kotlin bindings:
//        cd rust-engine/nurunuru-ffi/bindgen
//        make android-all   # builds arm64-v8a + x86_64 .so files → android/libs/
//        make kotlin        # generates Kotlin bindings → bindgen/kotlin-out/
//
//   2. Include this module in your Android project's settings.gradle.kts:
//        include(":nurunuru-ffi")
//        project(":nurunuru-ffi").projectDir = file("rust-engine/nurunuru-ffi/android")
//
//   3. Add the dependency in your app module:
//        implementation(project(":nurunuru-ffi"))
//
//   4. Use from Kotlin:
//        val client = NuruNuruBridge.create(context, nsecKey)
//        client.connect()
//        client.login(npubHex)
//        val feed = client.getRecommendedFeed(50u)

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.nurunuru"
    compileSdk = 34

    defaultConfig {
        minSdk = 26 // Android 8.0+
        targetSdk = 34

        // ABI filters for supported architectures
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].apply {
        // Generated Kotlin UniFFI bindings (produced by `make kotlin`)
        kotlin.srcDir("../bindgen/kotlin-out")

        // Pre-built native .so files (produced by `make android-all`)
        //   android/libs/arm64-v8a/libnurunuru_ffi.so
        //   android/libs/x86_64/libnurunuru_ffi.so
        jniLibs.srcDir("libs")

        // Hand-written Kotlin convenience wrappers
        kotlin.srcDir("src/main/kotlin")
    }
}

dependencies {
    // JNA runtime — required by the UniFFI-generated Kotlin bindings
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")

    // Coroutines for async bridging
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
