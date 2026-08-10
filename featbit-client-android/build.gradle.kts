plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "co.featbit.client.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":featbit-client"))
    implementation(libs.androidx.lifecycle.process)
}

// Publishes the `release` AAR + sources jar under `co.featbit:featbit-client-android:<version>`.
// See the sibling `featbit-client/build.gradle.kts` for the full rationale behind the version
// resolution rule — the same pattern is applied here so both modules pick up identical
// coordinates from JitPack's `VERSION` env var (or the `VERSION_NAME` gradle property locally).
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = providers.gradleProperty("GROUP").get()
            artifactId = "featbit-client-android"
            version = System.getenv("VERSION") ?: providers.gradleProperty("VERSION_NAME").get()
            afterEvaluate { from(components["release"]) }

            pom {
                name.set("FeatBit Client — Android Lifecycle Connector")
                description.set(
                    "Optional Android lifecycle glue for the FeatBit client SDK: wires " +
                        "ProcessLifecycleOwner + ConnectivityManager into `setForeground` / " +
                        "`setNetworkAvailable` so the underlying client suspends streaming while " +
                        "the app is backgrounded or offline."
                )
                url.set("https://github.com/Fluent-Health/featbit-android-sdk")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    url.set("https://github.com/Fluent-Health/featbit-android-sdk")
                    connection.set("scm:git:https://github.com/Fluent-Health/featbit-android-sdk.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Fluent-Health/featbit-android-sdk.git")
                }
            }
        }
    }
}
