plugins {
    id("eia.jvm-service")
}

application {
    // サブコマンド: migrate(所有者の資格情報でマイグレーション)/ serve(アプリのロールでリクエストを処理)。ADR-0024 §2
    mainClass.set("io.eia.order.app.MainKt")
}

dependencies {
    implementation(project(":services:order:adapters"))
    implementation(project(":platform:observability"))
    // 依存先ごとの Resilience は ResilienceMetrics 経由で作る(#8-c。Konsist の resilienceOnlyThroughMetrics)
    implementation(project(":platform:reliability"))
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.hikaricp)
    implementation(libs.logback.classic)
    runtimeOnly(libs.postgresql)

    integrationTestImplementation(project(":platform:test-support"))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.postgresql)
    integrationTestImplementation(libs.nimbus.jose.jwt)
    integrationTestImplementation(libs.ktor.client.cio)
    integrationTestImplementation(libs.kotlinx.coroutines.core)
}
