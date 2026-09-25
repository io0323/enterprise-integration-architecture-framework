import io.eia.buildlogic.coverageMinBound
import io.eia.buildlogic.koverEnforced
import io.eia.buildlogic.libs
import io.eia.buildlogic.version

// 品質ゲート: ktlint / detekt / Kover。全モジュールに適用する(kmp-* / jvm-* から適用される)。
plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
}

ktlint {
    version.set(libs.version("ktlint"))
    filter {
        // KSP など build/ 配下の生成コードは対象外
        exclude { it.file.path.contains("${File.separator}build${File.separator}") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    // KMP を含むすべてのソースセット(src/<sourceSet>/kotlin)を対象にする。resources 配下の .kt(Konsist の違反サンプル)は対象外。
    source.setFrom(
        fileTree("src") {
            include("*/kotlin/**/*.kt", "*/kotlin/**/*.kts")
        },
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
}

// detekt 1.23.8 は Kotlin 2.0.21 でビルドされている。KGP によるバージョン整列で detekt 実行用クラスパスの
// Kotlin が引き上げられると起動に失敗するため、detekt 用の構成だけ detekt がビルドされた版に固定する。
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(libs.version("detekt-kotlin"))
        }
    }
}

// 閾値は CODING_STANDARDS に合わせて設定する。強制は P01 から(eia.kover.enforce)。
kover {
    currentProject {
        // Kover は既定で全 Test タスクを計測・依存に加える。integrationTest を build に巻き込まないよう除外する。
        instrumentation {
            disabledForTestTasks.add("integrationTest")
        }
    }
    reports {
        verify {
            warningInsteadOfFailure = !koverEnforced()
            rule("行カバレッジ") {
                minBound(coverageMinBound())
            }
        }
    }
}
