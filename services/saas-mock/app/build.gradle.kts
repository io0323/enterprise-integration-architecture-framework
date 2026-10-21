plugins {
    id("eia.jvm-service")
    // CRM の顧客と Webhook の本文(JSON)
    id("org.jetbrains.kotlin.plugin.serialization")
}

// SaaS(CRM)の模擬(P10 ②。ADR-0035)。外部の契約 contracts/external/crm-api.v1.yaml を実装し、顧客の変更を署名した Webhook で送る。
// 自分たちの業務のサービスではなく外部のシステムの代わりなので、legacy-sim と同じく 4 つのモジュールに分けず app だけにする。
// 状態はメモリの上だけ(再起動で消える。ローカルと E2E の模擬のため)。
application {
    mainClass.set("io.eia.saasmock.app.MainKt")
}

dependencies {
    implementation(project(":shared:kernel"))
    // API トークン・Webhook の署名の鍵(SecretProvider。ADR-0019 §6)
    implementation(project(":platform:security"))
    // Webhook の署名(Standard Webhooks。ADR-0035 §2)
    implementation(project(":platform:webhook"))
    // 構造化 JSON ログ(logback-base.xml)
    implementation(project(":platform:observability"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
