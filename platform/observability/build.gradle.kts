plugins {
    id("eia.jvm-library")
}

// OTel の初期化・Ktor の Server / Client プラグイン・構造化 JSON ログ・マスキング(Framework 14。ADR-0018)。
// traceparent の解析と生成は shared/resilience、Correlation ID は shared/kernel を使う(ADR-0004 §5)。
dependencies {
    api(project(":shared:resilience"))
    api(libs.ktor.server.core)
    api(libs.ktor.client.core)
    api(libs.opentelemetry.api)
    api(libs.opentelemetry.sdk)
    // services の logback.xml が、このモジュールのエンコーダとアペンダを参照する
    api(libs.logback.classic)
    api(libs.slf4j.api)
    implementation(libs.opentelemetry.exporter.otlp) {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    implementation(libs.opentelemetry.exporter.sender.jdk)
    implementation(libs.opentelemetry.semconv)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.property)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.ktor.server.cio)
    integrationTestImplementation(libs.ktor.client.cio)
}
