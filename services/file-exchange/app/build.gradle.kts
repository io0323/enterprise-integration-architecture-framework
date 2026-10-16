plugins {
    id("eia.jvm-service")
}

application {
    // ファイル授受(ADR-0032)。S3 互換ストレージの受け口に届いたファイルを検査し、archive / quarantine に移す
    mainClass.set("io.eia.fileexchange.app.MainKt")
}

dependencies {
    implementation(project(":services:file-exchange:adapters"))
    implementation(project(":platform:batch"))
    implementation(project(":platform:file-transfer"))
    implementation(project(":platform:observability"))
    // DB のパスワードと S3 の資格情報(SecretProvider。ADR-0019 §6)
    implementation(project(":platform:security"))
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.opentelemetry.sdk.testing)

    // 統合テスト: PostgreSQL・S3 互換ストレージで、コマンド(migrate・receive・sweep)と常駐(serve)の全体を確かめる
    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    integrationTestImplementation(libs.ktor.client.cio)
    integrationTestImplementation(platform(libs.aws.sdk.bom))
    integrationTestImplementation(libs.aws.sdk.s3)
    integrationTestImplementation(libs.aws.sdk.url.connection.client)
}
