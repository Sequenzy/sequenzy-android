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

rootProject.name = "sequenzy-android-sdk"

include(":sequenzy-core")

// The Android library and example app need an Android SDK. The protocol core
// is plain Kotlin/JVM, so `./gradlew :sequenzy-core:test` works without one.
val localProperties = file("local.properties")
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProperties.exists() && localProperties.readText().contains("sdk.dir"))
if (hasAndroidSdk) {
    include(":sequenzy-android")
    include(":sequenzy-android-geofences")
    // The native half of the Flutter and React Native geofence plugins. It
    // lives next to this folder, so the public sequenzy-android copy has none.
    val geofenceBridge = file("../geofence-bridge/android")
    if (geofenceBridge.exists()) {
        include(":sequenzy-geofence-bridge")
        project(":sequenzy-geofence-bridge").projectDir = geofenceBridge
    }
    include(":example")
}
