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

    // convention plugin のテスト(#31)。TestKit で、実際の plugin を当てた小さなビルドを動かす
    testImplementation(gradleTestKit())
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
    // TestKit のビルドが、リポジトリと同じ版のカタログ(gradle/libs.versions.toml)を読むため
    systemProperty("eia.repositoryRoot", rootDir.parentFile.absolutePath)
}
