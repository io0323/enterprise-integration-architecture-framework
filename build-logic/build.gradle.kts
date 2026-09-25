plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.kotlin)
    implementation(libs.gradle.kotlin.serialization)
    implementation(libs.gradle.ksp)
    implementation(libs.gradle.kotest)
    implementation(libs.gradle.detekt)
    implementation(libs.gradle.ktlint)
    implementation(libs.gradle.kover)
}
