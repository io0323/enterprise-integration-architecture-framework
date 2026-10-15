plugins {
    id("eia.jvm-library")
    // 実行の属性(batch_run.attributes)とファイルのマニフェスト(.manifest.json)の JSON
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 軽量 DAG ランナー(Framework 7.2・7.4。ADR-0008・ADR-0030)。DAG の定義(Kotlin DSL)、Checkpoint、ステップのリトライ、
// 起動(時刻・前段の DAG の完了・ファイルの到着)、SLA の遅延の予測のメトリクス。
dependencies {
    api(project(":shared:kernel"))
    // ファイルの到着の監視の置き場(S3)とマニフェストは platform/file-transfer のもの(ADR-0032 §1)
    api(project(":platform:file-transfer"))
    // 実行・ステップの span と、traceparent の引き継ぎ(ADR-0018 §2)
    api(project(":platform:observability"))
    api(libs.opentelemetry.api)
    implementation(libs.opentelemetry.semconv)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk.testing)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    // ファイルの到着の統合テストで、バケットを作り、ファイルを置く(管理の identity)
    integrationTestImplementation(platform(libs.aws.sdk.bom))
    integrationTestImplementation(libs.aws.sdk.s3)
    integrationTestImplementation(libs.aws.sdk.url.connection.client)
}
