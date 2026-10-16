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
    // 日次の売上ファイルを S3 互換ストレージに置く(INT-SALES-004。ADR-0032)
    api(project(":platform:file-transfer"))
    // 週次の照合で order-service の日次の集計を読む: Client Credentials のトークン(ADR-0019 §4)と、
    // Timeout・Retry・Circuit Breaker・応答の分類(ADR-0021)
    api(project(":platform:security"))
    api(project(":platform:reliability"))
    api(libs.ktor.client.core)
    implementation(libs.kotlinx.coroutines.core)
    // 取り込みの位置(SourcePosition)の JSON
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // 売上ファイルの形が契約(contracts/files/sales_daily.v1.yaml)と一致することを確かめる
    testImplementation(libs.jackson3.dataformat.yaml)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.ktor.client.cio)
    // 売上ファイルの統合テストで、バケットを作る(管理の identity)
    integrationTestImplementation(platform(libs.aws.sdk.bom))
    integrationTestImplementation(libs.aws.sdk.s3)
    integrationTestImplementation(libs.aws.sdk.url.connection.client)
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
    val salesFileContract = rootProject.layout.projectDirectory.file("contracts/files/sales_daily.v1.yaml")
    inputs.file(salesFileContract).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("salesFileContract")
    systemProperty("eia.salesFileContract", salesFileContract.asFile.absolutePath)
}
