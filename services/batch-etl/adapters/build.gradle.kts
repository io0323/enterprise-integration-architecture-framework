plugins {
    id("eia.jvm-library")
    // 取り込む注文のイベントの型(契約の record 名の @SerialName。ADR-0025 §1)
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":services:batch-etl:application"))
    // 注文のイベントを読み、読めないものを DLQ に送る(ADR-0025・ADR-0028)
    api(project(":platform:messaging-kafka"))
    // 取り込んだ位置(Watermark)を、行と同じトランザクションで書く(ADR-0030 §3)
    api(project(":platform:batch"))
    implementation(project(":platform:observability"))
    implementation(libs.kotlinx.coroutines.core)
    // 取り込みの位置(SourcePosition)の JSON
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.ktor.client.cio)
}

// 取り込むイベントの契約のスキーマ(contracts/avro)をテストのリソースに含める(読み手の型が契約と合うことを確かめる)。
// 本番は書き手のスキーマを Apicurio から取る(WriterSchemas)
tasks.named<Test>("test") {
    systemProperty(
        "eia.contractsAvro",
        rootProject.layout.projectDirectory
            .dir("contracts/avro")
            .asFile.absolutePath,
    )
}
