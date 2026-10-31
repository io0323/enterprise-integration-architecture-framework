plugins {
    id("eia.jvm-library")
}

dependencies {
    api(project(":services:file-exchange:application"))
    // 受け口の検査・移動(S3 互換ストレージ。ADR-0032)
    api(project(":platform:file-transfer"))
    // 受け取りの実行のロック(ファイルの到着の実行と同じ advisory lock。ADR-0030 §2)と、実行の記録のスキーマ
    api(project(":platform:batch"))
    // 取引先への送信(SFTP)の Timeout・Retry・Circuit Breaker(ADR-0021)。Resilience は app で ResilienceMetrics から作って渡す
    api(project(":shared:resilience"))
    // 取引先の台帳(b2b-gateway と共有する 1 つの台帳。ADR-0040)と、鍵の期限のメトリクス
    api(project(":platform:trading-partner"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    // 売上ファイルの形が契約(contracts/files/sales_daily.v1.yaml)と一致することを確かめる
    testImplementation(libs.jackson3.dataformat.yaml)
    testImplementation(project(":platform:test-support"))

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    // バケットを作る(管理の identity)
    integrationTestImplementation(platform(libs.aws.sdk.bom))
    integrationTestImplementation(libs.aws.sdk.s3)
    integrationTestImplementation(libs.aws.sdk.url.connection.client)
    // 取引先の SFTP の不調(Toxiproxy)で、リトライと Circuit Breaker の遷移がメトリクスに出ることを確かめる
    integrationTestImplementation(project(":platform:reliability"))
    integrationTestImplementation(libs.testcontainers.toxiproxy)
    integrationTestImplementation(libs.opentelemetry.sdk.testing)
}

tasks.named<Test>("test") {
    val salesFileContract = rootProject.layout.projectDirectory.file("contracts/files/sales_daily.v1.yaml")
    inputs.file(salesFileContract).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("salesFileContract")
    systemProperty("eia.salesFileContract", salesFileContract.asFile.absolutePath)
}
