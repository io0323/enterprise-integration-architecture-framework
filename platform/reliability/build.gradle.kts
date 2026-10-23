plugins {
    id("eia.jvm-library")
}

// shared/resilience の JVM 向けアダプタ: OTel のメトリクス(Framework 13。ADR-0021 §7)。
// Ktor Client の呼び出しの結果の分類(HttpCallClassifier)と Retry-After は shared/integration-sdk/http に移した(KMP で共有。ADR-0037)。
// OTel は API だけを使う(SDK は platform/observability と services/*/app。ADR-0004 §4)。
dependencies {
    api(project(":shared:resilience"))
    api(libs.opentelemetry.api)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk.testing)
}
