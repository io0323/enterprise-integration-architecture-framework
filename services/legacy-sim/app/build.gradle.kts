plugins {
    id("eia.jvm-service")
}

// レガシー基幹の模擬(改修できないレガシー。ADR-0026)。業務のロジックを持たないため、4 つのモジュールに分けず app だけにする
application {
    // サブコマンド: migrate(所有者の資格情報で、レガシーの表と DBA の CDC の設定を作る)/ simulate(レガシーのアプリとして受注を書き換える)
    mainClass.set("io.eia.legacysim.app.MainKt")
}

dependencies {
    implementation(project(":shared:kernel"))
    // パスワードは SecretProvider から読む(CLAUDE.md §5 Security)
    implementation(project(":platform:security"))
    // 構造化 JSON ログ(logback-base.xml)
    implementation(project(":platform:observability"))
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    implementation(libs.postgresql)
    implementation(libs.logback.classic)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.kafka.clients)
    integrationTestImplementation(libs.kotlinx.serialization.json)
    // 生の CDC のトピックの Avro を読む(Apicurio の公式の Deserializer はテストでだけ使う。ADR-0025 §5)
    integrationTestImplementation(libs.apicurio.registry.avro.serde.kafka)
}

// CDC の統合テストは、ローカル基盤と同じコネクタの設定と Kafka Connect のイメージを使う。変更でテストをやり直すよう、入力に宣言する
tasks.named<Test>("integrationTest") {
    val infra = rootProject.layout.projectDirectory.dir("infra/local")
    inputs.dir(infra.dir("kafka-connect")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("connectors")
    inputs.dir(infra.dir("images/kafka-connect")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("kafkaConnectImage")
}
