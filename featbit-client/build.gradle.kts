plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

android {
    namespace = "co.featbit.client"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    testOptions {
        unitTests.all {
            // Surface Testcontainers' Docker-environment diagnostics during E2E runs.
            it.systemProperty("org.slf4j.simpleLogger.log.org.testcontainers", "debug")
            // docker-java defaults to API 1.32, which modern Docker daemons (>=25, min API
            // 1.44) reject. It reads the negotiated version from this system property.
            it.systemProperty("api.version", System.getProperty("api.version", "1.44"))
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    testImplementation(libs.testcontainers)
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

// Publishes the `release` AAR + sources jar under `co.featbit:featbit-client:<version>`.
// `./gradlew publishToMavenLocal` publishes to `~/.m2/repository` with no extra config, so
// consumers can smoke-test the artifact locally before any remote repository exists.
//
// Version resolution prefers JitPack's `VERSION` env var (populated with the git tag being
// built, e.g. `v0.1.0`) so the file names JitPack looks for match what Gradle publishes. The
// `VERSION_NAME` gradle property is the local fallback for `publishToMavenLocal`.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = providers.gradleProperty("GROUP").get()
            artifactId = "featbit-client"
            version = System.getenv("VERSION") ?: providers.gradleProperty("VERSION_NAME").get()
            afterEvaluate { from(components["release"]) }

            pom {
                name.set("FeatBit Client")
                description.set(
                    "FeatBit feature-flag client SDK for Kotlin/Android — local evaluation " +
                        "with real-time WebSocket streaming and lifecycle-aware sync."
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
