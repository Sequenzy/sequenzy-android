import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

allprojects {
    group = "com.sequenzy"
    version = "0.1.3"
}

// Maven Central metadata shared by sequenzy-core and sequenzy-android. The
// publish workflow (.github/workflows/publish-mobile-sdks.yml) passes the
// Central Portal token and the signing key as ORG_GRADLE_PROJECT_* variables;
// without a signing key (local runs, pull request checks) publications are
// left unsigned so publishToMavenLocal still works.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
            if (project.hasProperty("signingInMemoryKey")) {
                signAllPublications()
            }
            pom {
                url.set("https://sequenzy.com")
                organization {
                    name.set("Sequenzy")
                    url.set("https://sequenzy.com")
                }
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("sequenzy")
                        name.set("Sequenzy")
                        url.set("https://sequenzy.com")
                    }
                }
                scm {
                    url.set("https://github.com/Sequenzy/sequenzy-android")
                    connection.set("scm:git:https://github.com/Sequenzy/sequenzy-android.git")
                }
            }
        }
    }
}
