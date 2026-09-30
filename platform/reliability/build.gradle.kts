plugins {
    id("eia.jvm-library")
}

// shared/resilience の JVM 向けアダプタ: OTel のメトリクス、Ktor Client の呼び出しの結果の分類(Framework 13。ADR-0021 §3・§7)。
// OTel は API だけを使う(SDK は platform/observability と services/*/app。ADR-0004 §4)。
dependencies {
    api(project(":shared:resilience"))
    api(libs.ktor.client.core)
    api(libs.opentelemetry.api)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.opentelemetry.sdk.testing)
}
