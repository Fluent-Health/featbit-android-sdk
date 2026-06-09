plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
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
