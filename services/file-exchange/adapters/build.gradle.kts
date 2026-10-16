plugins {
    id("eia.jvm-library")
}

dependencies {
    api(project(":services:file-exchange:application"))
    // 受け口の検査・移動(S3 互換ストレージ。ADR-0032)
    api(project(":platform:file-transfer"))
    // 受け取りの実行のロック(ファイルの到着の実行と同じ advisory lock。ADR-0030 §2)と、実行の記録のスキーマ
    api(project(":platform:batch"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    // 売上ファイルの形が契約(contracts/files/sales_daily.v1.yaml)と一致することを確かめる
    testImplementation(libs.jackson3.dataformat.yaml)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    // バケットを作る(管理の identity)
    integrationTestImplementation(platform(libs.aws.sdk.bom))
    integrationTestImplementation(libs.aws.sdk.s3)
    integrationTestImplementation(libs.aws.sdk.url.connection.client)
}

tasks.named<Test>("test") {
    val salesFileContract = rootProject.layout.projectDirectory.file("contracts/files/sales_daily.v1.yaml")
    inputs.file(salesFileContract).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("salesFileContract")
    systemProperty("eia.salesFileContract", salesFileContract.asFile.absolutePath)
}
