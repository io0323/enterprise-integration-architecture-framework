plugins {
    id("eia.jvm-library")
}

// API の共通部品(Framework 5。ADR-0022): Problem Details(RFC 9457)と、Idempotency-Key(②b)。
// platform 間の依存は Konsist で許可した一覧だけ(observability: 応答の correlationId)。
dependencies {
    api(project(":shared:kernel"))
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
