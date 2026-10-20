plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

mavenPublishing {
    coordinates("com.sequenzy", "sequenzy-core", version.toString())
    pom {
        name.set("Sequenzy core")
        description.set("Protocol client, identity and event queue shared by the Sequenzy Android SDK.")
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("sequenzy.contract", rootProject.file("../contract/fixtures").absolutePath)
}
