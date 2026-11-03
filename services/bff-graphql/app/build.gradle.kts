plugins {
    id("eia.jvm-service")
}

application {
    // 画面のための集約(GraphQL BFF。ADR-0043)。データを持たず、注文・在庫・出荷を読んで返す
    mainClass.set("io.eia.bffgraphql.app.MainKt")
}

dependencies {
    implementation(project(":services:bff-graphql:adapters"))
    implementation(project(":platform:api"))
    implementation(project(":platform:grpc"))
    implementation(project(":platform:observability"))
    // 下流の Resilience を、メトリクスに登録して作る(ADR-0021)
    implementation(project(":platform:reliability"))
    implementation(project(":platform:security"))
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    // 統合テスト: 実際の mTLS・JWT・gRPC で、起動から応答までの全体を確かめる。下流は偽物(コンテナは使わない)
    integrationTestImplementation(project(":platform:grpc-stubs"))
    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.nimbus.jose.jwt)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
    integrationTestImplementation(libs.kotlinx.serialization.json)
}
