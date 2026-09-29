plugins {
    id("eia.jvm-service")
}

// 監査記録の改竄の検査(`make audit-verify SERVICE=<name>`)。チェーンの検証とアンカーとの照合を行い、結果を終了コードで返す(ADR-0017)。
// 終了コードをそのまま返すため、`run` ではなく `installDist` の起動スクリプトから実行する(scripts/audit-verify.sh)。
application {
    mainClass.set("io.eia.tools.auditverify.MainKt")
    applicationName = "audit-verify"
}

dependencies {
    implementation(project(":platform:audit"))

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    // 統合テストで管理者の S3 クライアントを使う(バケットの作成)
    integrationTestImplementation(platform(libs.aws.sdk.bom))
    integrationTestImplementation(libs.aws.sdk.s3)
    integrationTestImplementation(libs.aws.sdk.url.connection.client)
}
