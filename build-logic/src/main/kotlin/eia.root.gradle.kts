import io.eia.buildlogic.koverEnforced
import io.eia.buildlogic.requireArm64JdkOnAppleSilicon

// ルートプロジェクト用: Kover の全体集約(全体 75%)。
plugins {
    id("org.jetbrains.kotlinx.kover")
}

// macosArm64 のテストが黙ってスキップされないよう、構成時に Gradle の JVM のアーキテクチャを検査する(README「開発環境の前提」)
requireArm64JdkOnAppleSilicon()

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
