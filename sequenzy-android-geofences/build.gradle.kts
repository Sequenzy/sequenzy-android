plugins {
    id("com.android.library")
    kotlin("android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "com.sequenzy.android.geofences"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

mavenPublishing {
    coordinates("com.sequenzy", "sequenzy-android-geofences", version.toString())
    pom {
        name.set("Sequenzy geofencing for Android")
        description.set(
            "Report when signed-in users enter, leave or stay in your geofences, with Google Play services location.",
        )
    }
}

dependencies {
    api(project(":sequenzy-android"))
    // A separate artifact so apps without geofencing never pull in Play
    // services location or declare location use.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
}
