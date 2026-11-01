plugins {
    id("eia.jvm-library")
}

// テスト専用の部品(ADR-0016 §5)。各モジュールは testImplementation / integrationTestImplementation からだけ参照する(Konsist で検査)。
dependencies {
    api(libs.testcontainers)
    // Kafka のコンテナ(KafkaBrokerContainer。起動の競合を避ける。#170)
    api(libs.testcontainers.kafka)
    // テスト用の SSH と PGP の鍵を作る(TestKeys。platform/file-transfer のテスト)
    implementation(libs.bouncycastle.pg)
    implementation(libs.bouncycastle.pkix)
}
