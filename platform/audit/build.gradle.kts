plugins {
    id("eia.jvm-library")
    // アンカー(anchors/{service}/{date}.json)の JSON
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 監査記録: 追記専用テーブル + ハッシュチェーン、S3 互換ストレージ(Object Lock)へのアンカー保存と照合(Framework 14.1。ADR-0008・ADR-0017)。
dependencies {
    api(project(":shared:kernel"))
    // S3 の資格情報は SecretProvider から取る(ADR-0008・ADR-0019 §6)
    api(project(":platform:security"))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    // details の値は必ず Masking を通す(ADR-0018 §3)
    implementation(project(":platform:observability"))
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        // HTTP の実装は url-connection-client だけにする(ADR-0017)
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation(libs.aws.sdk.url.connection.client)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.property)
    testImplementation(libs.mockk)
    // AuditMetrics の単体テスト(InMemoryMetricReader)
    testImplementation(libs.opentelemetry.sdk.testing)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.hikaricp)
}
