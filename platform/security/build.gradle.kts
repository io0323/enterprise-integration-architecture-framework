plugins {
    id("eia.jvm-library")
}

// JWT 検証(JWKS)・スコープ認可・Client Credentials のトークン取得・SecretProvider(Framework 12。ADR-0008・ADR-0019)。
// Nimbus JOSE+JWT を参照してよいのはこのモジュールだけ(Konsist の nimbusOnlyInSecurity)。
dependencies {
    api(project(":shared:kernel"))
    api(libs.ktor.server.auth)
    api(libs.ktor.client.core)
    api(libs.opentelemetry.api)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.opentelemetry.sdk.testing)
    // ログとエラーのメッセージに鍵・トークンが出ないことを、② のマスキング(EiaLogEncoder)と組み合わせて確かめる
    testImplementation(project(":platform:observability"))

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.ktor.client.cio)
}
