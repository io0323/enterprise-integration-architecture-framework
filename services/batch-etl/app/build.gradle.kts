plugins {
    id("eia.jvm-service")
    // 統合テストで送る注文のイベントの型(@Serializable)
    id("org.jetbrains.kotlin.plugin.serialization")
}

application {
    // 注文の分析のバッチ(ADR-0031)。日次の DAG で注文のイベントを取り込み、日次のパーティションを作る
    mainClass.set("io.eia.batchetl.app.MainKt")
}

dependencies {
    implementation(project(":services:batch-etl:adapters"))
    implementation(project(":platform:batch"))
    // 日次の売上ファイルの置き場(S3 互換ストレージ。ADR-0032)
    implementation(project(":platform:file-transfer"))
    implementation(project(":platform:observability"))
    implementation(project(":platform:schema-registry"))
    // DB のパスワード(SecretProvider。ADR-0019 §6)
    implementation(project(":platform:security"))
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.opentelemetry.sdk.testing)

    // 統合テスト: Kafka・Apicurio Registry・PostgreSQL で、コマンド(migrate・run)と常駐(serve)の全体を確かめる
    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    integrationTestImplementation(libs.ktor.client.cio)
    // 日次の売上ファイルの置き場(SeaweedFS)のバケットを作る
    integrationTestImplementation(platform(libs.aws.sdk.bom))
    integrationTestImplementation(libs.aws.sdk.s3)
    integrationTestImplementation(libs.aws.sdk.url.connection.client)
    // 置き場が止まっても、取り込みの DAG と SLA のメトリクスに影響しないことを、メトリクスを読んで確かめる(P09 ⑥)
    integrationTestImplementation(libs.opentelemetry.sdk.testing)
}

tasks.named<Test>("integrationTest") {
    inputs
        .dir(rootProject.layout.projectDirectory.dir("contracts/avro/sales"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("contracts")
}
