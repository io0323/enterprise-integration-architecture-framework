import io.eia.buildlogic.koverEnforced
import io.eia.buildlogic.koverWithIntegrationTests
import io.eia.buildlogic.libs
import io.eia.buildlogic.requireArm64JdkOnAppleSilicon
import io.eia.buildlogic.version
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

// ルートプロジェクト用: Kover の全体集約(全体 75%)。
plugins {
    id("org.jetbrains.kotlinx.kover")
}

// macosArm64 のテストが黙ってスキップされないよう、構成時に Gradle の JVM のアーキテクチャを検査する(README「開発環境の前提」)
requireArm64JdkOnAppleSilicon()

kover {
    reports {
        verify {
            // 集約に統合テストを持つモジュール(services/*/adapters)を含むため、統合テストを含めて測るときだけ強制する(MODULE_DESIGN §5)
            warningInsteadOfFailure = !koverEnforced() || !koverWithIntegrationTests()
            rule("全体の行カバレッジ") {
                minBound(75)
            }
        }
    }
}

// Kotlin/JS のテストランナー(mocha)の間接的な依存を、脆弱性の修正版に固定する(Dependabot alerts。docs/runbooks/dependabot-kotlin-js.md)。
// 版を変えたら `./gradlew kotlinUpgradeYarnLock` で kotlin-js-store/yarn.lock を作り直す。
plugins.withType<YarnPlugin> {
    the<YarnRootExtension>().apply {
        resolution("serialize-javascript", libs.version("npm-serialize-javascript"))
        resolution("diff", libs.version("npm-diff"))
    }
}
