import io.eia.buildlogic.koverEnforced

// ルートプロジェクト用: Kover の全体集約(全体 75%)。
plugins {
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        verify {
            warningInsteadOfFailure = !koverEnforced()
            rule("全体の行カバレッジ") {
                minBound(75)
            }
        }
    }
}
