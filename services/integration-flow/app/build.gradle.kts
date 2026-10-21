plugins {
    id("eia.jvm-service")
}

application {
    // iPaaS 風の Flow の実行(ADR-0036)。サブコマンド: validate(Flow の定義の検証。P10 ④)。serve は P10 ⑤
    mainClass.set("io.eia.integrationflow.app.MainKt")
}

dependencies {
    implementation(project(":services:integration-flow:adapters"))
    // 構造化 JSON ログ(logback-base.xml)
    implementation(project(":platform:observability"))
    implementation(libs.logback.classic)
}
