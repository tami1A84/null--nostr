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

// Enabled Rust FFI bindings:
include(":nurunuru-ffi")
project(":nurunuru-ffi").projectDir = file("../rust-engine/nurunuru-ffi/android")
