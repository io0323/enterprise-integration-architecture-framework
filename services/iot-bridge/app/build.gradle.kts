plugins {
    id("eia.jvm-service")
}

application {
    // MQTT から Kafka への橋渡し(ADR-0039)。サブコマンド: serve / reprocess-dlq(DLQ をデコードからやり直す。§4)。ADR-0024 §2
    mainClass.set("io.eia.iotbridge.app.MainKt")
}

dependencies {
    implementation(project(":services:iot-bridge:adapters"))
    // 構造化 JSON ログ(logback-base.xml)・OTel
    implementation(project(":platform:observability"))
    // 起動時のスキーマ ID の解決(ADR-0025 §3)
    implementation(project(":platform:schema-registry"))
    // ブリッジのユーザーのパスワード(SecretProvider。ADR-0019 §6)
    implementation(project(":platform:security"))
    implementation(libs.ktor.client.cio)
    // ヘルスチェックのサーバは CIO。Netty の版は HiveMQ MQTT Client(Netty 4.1)と Ktor の Netty のエンジンで合わないため使わない
    implementation(libs.ktor.server.cio)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.core)

    // 統合テスト: Mosquitto(ACL つき)→ iot-bridge(serve)→ Kafka、DLQ の再処理(reprocess-dlq)
    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(project(":shared:integration-sdk:mqtt"))
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    integrationTestImplementation(libs.ktor.client.cio)
}
