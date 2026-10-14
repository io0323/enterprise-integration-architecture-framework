plugins {
    id("eia.jvm-library")
    // 実行の属性(batch_run.attributes)とファイルのマニフェスト(.manifest.json)の JSON
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 軽量 DAG ランナー(Framework 7.2・7.4。ADR-0008・ADR-0030)。DAG の定義(Kotlin DSL)、Checkpoint、ステップのリトライ、
// 起動(時刻・前段の DAG の完了・ファイルの到着)、SLA の遅延の予測のメトリクス。
dependencies {
    api(project(":shared:kernel"))
    // ファイルの到着の監視の S3 の資格情報は SecretProvider から取る(ADR-0019 §6)
    api(project(":platform:security"))
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
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        // HTTP の実装は url-connection-client だけにする(ADR-0017)
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation(libs.aws.sdk.url.connection.client)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk.testing)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
}
