plugins {
    id("eia.jvm-service")
}

application {
    // サブコマンド: migrate(所有者の資格情報でマイグレーション)/ serve(アプリのロールでリクエストを処理)。ADR-0024 §2
    mainClass.set("io.eia.order.app.MainKt")
}

dependencies {
    implementation(project(":services:order:adapters"))
    implementation(project(":platform:observability"))
    // 監査の記録(AuditLog)とメトリクス(AuditMetrics)を配線する(ADR-0017)
    implementation(project(":platform:audit"))
    // 依存先ごとの Resilience は ResilienceMetrics 経由で作る(#8-c。Konsist の resilienceOnlyThroughMetrics)
    implementation(project(":platform:reliability"))
    // 注文のイベントの発行(Outbox)と、起動時のスキーマ ID の解決(ADR-0007・ADR-0025 §3)
    implementation(project(":platform:outbox"))
    implementation(project(":platform:schema-registry"))
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.hikaricp)
    implementation(libs.logback.classic)
    runtimeOnly(libs.postgresql)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.nimbus.jose.jwt)
    integrationTestImplementation(libs.bouncycastle.pkix)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    // 統合テストで Schema Registry を立て、契約を登録する
    integrationTestImplementation(libs.ktor.client.cio)
}
