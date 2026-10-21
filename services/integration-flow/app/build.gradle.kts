plugins {
    id("eia.jvm-service")
}

application {
    // iPaaS 風の Flow の実行(ADR-0036)。サブコマンド: migrate / serve / validate(Flow の定義の検証)。ADR-0024 §2
    mainClass.set("io.eia.integrationflow.app.MainKt")
}

dependencies {
    implementation(project(":services:integration-flow:adapters"))
    // 構造化 JSON ログ(logback-base.xml)・OTel
    implementation(project(":platform:observability"))
    // 起動時のスキーマ ID の解決(ADR-0025 §3)・Outbox・冪等消費の記録の掃除
    implementation(project(":platform:schema-registry"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:inbox"))
    // 依存先ごとの Resilience は ResilienceMetrics 経由で作る(#8-c。Konsist の resilienceOnlyThroughMetrics)
    implementation(project(":platform:reliability"))
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    // 統合テスト: Kafka の取り込みのトピック → integration-flow → Outbox(PostgreSQL の WAL)と、CRM の API の定期の同期
    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.kotlinx.serialization.json)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    integrationTestImplementation(libs.ktor.client.cio)
}
