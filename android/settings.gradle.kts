pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nurunuru-android"
include(":app")

// Uncomment after building Rust FFI bindings:
//   cd ../rust-engine/nurunuru-ffi/bindgen && make android-all && make kotlin
// include(":nurunuru-ffi")
// project(":nurunuru-ffi").projectDir = file("../rust-engine/nurunuru-ffi/android")
