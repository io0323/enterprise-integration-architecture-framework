plugins {
    id("eia.jvm-library")
}

// Webhook の署名(Standard Webhooks の形。Framework 10.2・12。ADR-0035 §2)。送り手(saas-mock)の署名と、受け口(webhook-receiver)の検証。
// 鍵は SecretProvider から読んだ Secret を受け取る。HTTP のフレームワークには依存しない(ヘッダの値と本文のバイト列を受け取る)。
dependencies {
    api(project(":shared:kernel"))
    api(project(":platform:security"))
}
