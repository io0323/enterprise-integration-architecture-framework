plugins {
    id("eia.jvm-library")
}

// テスト専用の部品(ADR-0016 §5)。各モジュールは testImplementation / integrationTestImplementation からだけ参照する(Konsist で検査)。
dependencies {
    api(libs.testcontainers)
}
