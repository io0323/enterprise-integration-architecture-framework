plugins {
    id("eia.jvm-library")
}

dependencies {
    api(project(":services:order:application"))
    // 監査のテーブルのマイグレーションを、order の DB に同じく適用する(ADR-0017)
    implementation(project(":platform:audit"))
    // 永続化: Exposed はトランザクションの管理に使い、ロックの意味が重要な SQL は PreparedStatement で書く(MODULE_DESIGN §3)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.mockk)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
}
