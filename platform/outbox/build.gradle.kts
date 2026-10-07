plugins {
    id("eia.jvm-library")
    // テストのイベントの型(@Serializable)
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Transactional Outbox(Framework 8.3。ADR-0007)。業務の更新と同じトランザクションで Outbox の表に INSERT し、同じ行を DELETE する。
// 発行は Debezium の Outbox Event Router が WAL の INSERT から行う(P06 ③)。保持期間の方式は #76。
dependencies {
    api(project(":shared:kernel"))
    // 記録のトピック・ヘッダ(EventTopic・EventMetadata)とペイロード(AvroEventSerializer。ADR-0025)
    api(project(":platform:messaging-kafka"))
    // 記録を作るときの span(ADR-0018 §2)
    api(project(":platform:observability"))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.opentelemetry.api)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.mockk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.ktor.client.mock)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
}
