plugins {
    id("eia.jvm-library")
    // コマンドと返事のイベントの型(契約の record 名の @SerialName。ADR-0025 §1)
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":services:payment:application"))
    // コマンドの受信(EventConsumer。ADR-0028)と、返事の発行(Outbox。ADR-0007)・冪等消費の記録(processed_message)
    api(project(":platform:messaging-kafka"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:inbox"))
    implementation(project(":platform:observability"))
    // 永続化: Exposed はトランザクションの管理に使い、ロックの意味が重要な SQL は PreparedStatement で書く(MODULE_DESIGN §3)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.opentelemetry.sdk.testing)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
}

// 返事のイベントの契約のスキーマ(contracts/avro)をリソースに含める。契約が唯一の真実で、コードに複製しない(ADR-0025 §2)
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/avro/payment")) {
        include("PaymentAuthorized.avsc", "PaymentDeclined.avsc", "PaymentVoided.avsc")
        into("contracts/avro/payment")
    }
}

// テストで、リソースのスキーマが契約のファイルと同じであること・コマンドの型が契約のスキーマで読み書きできることを確かめる
tasks.named<Test>("test") {
    systemProperty(
        "eia.contractsAvro",
        rootProject.layout.projectDirectory
            .dir("contracts/avro")
            .asFile.absolutePath,
    )
}
