plugins {
    id("eia.jvm-library")
    // 出力のイベントの型(契約の record 名の @SerialName。ADR-0025 §1)
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":services:iot-bridge:application"))
    // Kafka への発行と DLQ(ADR-0025・ADR-0039 §4)
    api(project(":platform:messaging-kafka"))
    implementation(project(":platform:observability"))
    // デバイスのペイロードを形式のスキーマ(contracts/devices の JSON Schema)で検証する。契約が唯一の真実で、コードに複製しない(ADR-0039 §4)
    implementation(libs.json.schema.validator)
    // MQTT の購読(手動の ack。ADR-0038 §3)。使ってよいのはこのモジュールだけ(Konsist)
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.ktor.client.mock)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(project(":platform:inbox"))
    integrationTestImplementation(project(":shared:integration-sdk:mqtt"))
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.ktor.client.cio)
}

// デバイスの形式のスキーマと、出力のイベントの契約のスキーマをリソースに含める(版を足すときは、契約にファイルを足すだけでリソースに入る)
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/devices")) {
        include("*.schema.json")
        into("contracts/devices")
    }
    from(rootProject.layout.projectDirectory.file("contracts/avro/iot/TelemetryReported.avsc")) {
        into("contracts/avro/iot")
    }
}

// テストで、リソースのスキーマが契約のファイルと同じであることを確かめる
tasks.named<Test>("test") {
    systemProperty(
        "eia.contractsDevices",
        rootProject.layout.projectDirectory
            .dir("contracts/devices")
            .asFile.absolutePath,
    )
    systemProperty(
        "eia.contractsAvro",
        rootProject.layout.projectDirectory
            .dir("contracts/avro")
            .asFile.absolutePath,
    )
}

// 統合テスト(MosquittoBrokerIT)で、ローカル基盤の Mosquitto の設定と起動のスクリプト(infra/local/mosquitto)をそのまま使う
tasks.named<Test>("integrationTest") {
    systemProperty(
        "eia.infraLocal",
        rootProject.layout.projectDirectory
            .dir("infra/local")
            .asFile.absolutePath,
    )
}
