plugins {
    id("eia.jvm-service")
}

application {
    // CRM(SaaS)の Webhook の受け口(ADR-0035)。サブコマンド: migrate / serve(ADR-0024 §2)
    mainClass.set("io.eia.webhookreceiver.app.MainKt")
}

dependencies {
    implementation(project(":services:webhook-receiver:adapters"))
    implementation(project(":platform:observability"))
    implementation(project(":platform:schema-registry"))
    implementation(project(":platform:outbox"))
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    // 統合テスト: mTLS(ゲートウェイの証明書)→ 署名の検証 → 受け取りの記録と Outbox(PostgreSQL の WAL で確かめる)
    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    integrationTestImplementation(libs.ktor.client.cio)
}
