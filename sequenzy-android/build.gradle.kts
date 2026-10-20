plugins {
    id("com.android.library")
    kotlin("android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "com.sequenzy.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

// The publish plugin adds the release variant with sources and javadoc jars.
mavenPublishing {
    coordinates("com.sequenzy", "sequenzy-android", version.toString())
    pom {
        name.set("Sequenzy for Android")
        description.set(
            "Identify contacts, track events, receive push notifications and show in-app messages from Android apps.",
        )
    }
}

dependencies {
    api(project(":sequenzy-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    // Apps that receive push already depend on Firebase Messaging; the SDK
    // only compiles against it so apps without push do not pull it in.
    compileOnly("com.google.firebase:firebase-messaging:24.1.0")
}
