plugins {
    id("eia.jvm-service")
}

application {
    // B2B / EDI Gateway(ADR-0040・ADR-0041)。取引先の SFTP から受注の文書を受け取り、検査・応答・社内への取り込みを行う
    mainClass.set("io.eia.b2bgateway.app.MainKt")
}

dependencies {
    implementation(project(":services:b2b-gateway:adapters"))
    implementation(project(":platform:observability"))
    // 取引先の SFTP と order の API の Resilience を、メトリクスに登録して作る(ADR-0021)
    implementation(project(":platform:reliability"))
    // DB のパスワード・S3 の資格情報・PGP の鍵・クライアントの秘密(SecretProvider。ADR-0019 §6)
    implementation(project(":platform:security"))
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(project(":platform:test-support"))

    // 統合テスト: PostgreSQL・S3 互換ストレージ・SFTP と、order の API と Keycloak の代わり(Ktor のサーバ)で、コマンドと常駐の全体を確かめる
    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    integrationTestImplementation(libs.ktor.server.netty)
    integrationTestImplementation(platform(libs.aws.sdk.bom))
    integrationTestImplementation(libs.aws.sdk.s3)
    integrationTestImplementation(libs.aws.sdk.url.connection.client)
}
