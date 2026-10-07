plugins {
    id("eia.jvm-library")
}

// テスト専用の部品(ADR-0016 §5)。各モジュールは testImplementation / integrationTestImplementation からだけ参照する(Konsist で検査)。
dependencies {
    api(libs.testcontainers)
}

dependencies {
    // compose と同じ構成のコンテナ(Kafka Connect)の統合テストで、ブローカーを立てる
    integrationTestImplementation(libs.testcontainers.kafka)
}
