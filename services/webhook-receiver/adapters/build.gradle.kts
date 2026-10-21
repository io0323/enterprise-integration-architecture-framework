plugins {
    id("eia.jvm-library")
    // 取り込みのイベントの型(契約の record 名の @SerialName。ADR-0025 §1)
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":services:webhook-receiver:application"))
    // 署名の検証(Standard Webhooks。ADR-0035 §2)。鍵は SecretProvider から読む(ADR-0019 §6)
    api(project(":platform:webhook"))
    api(project(":platform:security"))
    // 401 / 503 を Problem Details で返す(ADR-0022 §2)
    api(project(":platform:api"))
    // 取り込みのトピックへの発行(Outbox。ADR-0007)
    api(project(":platform:messaging-kafka"))
    implementation(project(":platform:outbox"))
    implementation(project(":platform:observability"))
    // 永続化: Exposed はトランザクションの管理に使い、ロックの意味が重要な SQL は PreparedStatement で書く(MODULE_DESIGN §3)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.opentelemetry.api)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.opentelemetry.sdk.testing)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
}

// 取り込みのイベントの契約のスキーマ(contracts/avro)をリソースに含める。契約が唯一の真実で、コードに複製しない(ADR-0025 §2)
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/avro/integration")) {
        include("WebhookReceived.avsc")
        into("contracts/avro/integration")
    }
}

// テストで、リソースのスキーマが契約のファイルと同じであることを確かめる
tasks.named<Test>("test") {
    systemProperty(
        "eia.contractsAvro",
        rootProject.layout.projectDirectory
            .dir("contracts/avro")
            .asFile.absolutePath,
    )
}
