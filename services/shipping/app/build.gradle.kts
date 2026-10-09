plugins {
    id("eia.jvm-service")
}

application {
    // 出荷(模擬)(注文 Saga の参加者。ADR-0029)。コマンドを読み、返事を Outbox で書く
    mainClass.set("io.eia.shipping.app.MainKt")
}

dependencies {
    implementation(project(":services:shipping:adapters"))
    implementation(project(":platform:observability"))
    implementation(project(":platform:schema-registry"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:inbox"))
    // DB のパスワード(SecretProvider。ADR-0019 §6)
    implementation(project(":platform:security"))
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    // 統合テスト: PostgreSQL → shipping → Outbox → Debezium(compose と同じイメージ・コネクタの設定)→ Kafka の全体(ADR-0029)
    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.kotlinx.serialization.json)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    integrationTestImplementation(libs.ktor.client.cio)
}

// 統合テストは、ローカル基盤と同じコネクタの設定(infra/local/kafka-connect)と Connect のイメージを使う。変更でテストをやり直すよう、入力に宣言する
tasks.named<Test>("integrationTest") {
    val infra = rootProject.layout.projectDirectory.dir("infra/local")
    inputs.dir(infra.dir("kafka-connect")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("connectors")
    inputs.dir(infra.dir("images/kafka-connect")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("kafkaConnectImage")
    inputs
        .dir(rootProject.layout.projectDirectory.dir("contracts/avro/shipping"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("contracts")
}
