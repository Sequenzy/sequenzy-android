plugins {
    id("com.android.application")
    kotlin("android")
}

// Point the example at a local API (the emulator reaches the host as
// 10.0.2.2) or production with -PsequenzyApiBaseUrl=... and a key whose
// allowed app IDs include com.sequenzy.example.
fun prop(name: String, default: String) = (findProperty(name) as String?) ?: default

android {
    namespace = "com.sequenzy.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sequenzy.example"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SEQUENZY_PUBLIC_KEY", "\"${prop("sequenzyPublicKey", "seq_pk_replace_me_00000000000000000000")}\"")
        buildConfigField("String", "SEQUENZY_COMPANY_ID", "\"${prop("sequenzyCompanyId", "replace-me")}\"")
        buildConfigField("String", "SEQUENZY_API_BASE_URL", "\"${prop("sequenzyApiBaseUrl", "http://10.0.2.2:4000")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":sequenzy-android"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
