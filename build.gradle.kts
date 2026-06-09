plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "8.9"
    distributionType = Wrapper.DistributionType.BIN
    validateDistributionUrl = false
}
