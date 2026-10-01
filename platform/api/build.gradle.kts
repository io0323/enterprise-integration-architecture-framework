plugins {
    id("eia.jvm-library")
}

// API の共通部品(Framework 5。ADR-0022): Problem Details(RFC 9457)と、Idempotency-Key(②b)。
// platform 間の依存は Konsist で許可した一覧だけ(observability: 応答の correlationId)。
dependencies {
    api(project(":shared:kernel"))
    // リクエストの締め切り(CallDeadline)を、入れ子の Resilience と DB の打ち切りに引き継ぐ(ADR-0021 §12・ADR-0024)
    api(project(":shared:resilience"))
    api(libs.ktor.server.core)
    implementation(project(":platform:observability"))
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.logback.classic)
    // ServerObservability の RED メトリクスに、Problem Details の状態コードが記録されることを確かめる
    testImplementation(libs.opentelemetry.sdk.testing)
}
