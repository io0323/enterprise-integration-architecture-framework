package io.eia.buildlogic

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File

private val repositoryRoot = File(checkNotNull(System.getProperty("eia.repositoryRoot")) { "Gradle の test タスクから実行してください" })

/**
 * 統合テスト(eia.jvm-library の integrationTest)は、結果をビルドキャッシュから取らず、前回の結果でも済ませない(#105 の後)。
 * 統合テストは Gradle の入力に宣言していないファイル(infra/local の設定・契約)と、実際のミドルウェアの振る舞いに依存するため。
 */
class IntegrationTestCachingSpec :
    FunSpec({
        val project = tempdir()

        fun write(
            path: String,
            text: String,
        ) = File(project, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent())

        write(
            "settings.gradle.kts",
            """
            dependencyResolutionManagement {
                repositories { mavenCentral() }
                versionCatalogs { create("libs") { from(files("${File(repositoryRoot, "gradle/libs.versions.toml").invariantSeparatorsPath}")) } }
            }
            rootProject.name = "caching-fixture"
            include(":it")
            """,
        )
        write("build.gradle.kts", "")
        write("gradle.properties", "org.gradle.caching=true")
        // テストのタスクの入力(ADR-0016 §5。Testcontainers のイメージ)。このテストは使わないので空でよい
        write("infra/local/images.env", "")
        write("it/build.gradle.kts", "plugins { id(\"eia.jvm-library\") }")
        write(
            "it/src/integrationTest/kotlin/SmokeIT.kt",
            """
            import io.kotest.core.spec.style.FunSpec

            class SmokeIT : FunSpec({ test("runs") { } })
            """,
        )

        fun integrationTest() =
            GradleRunner
                .create()
                .withProjectDir(project)
                .withPluginClasspath()
                .withArguments(":it:integrationTest", "--build-cache", "--stacktrace")
                .build()
                .task(":it:integrationTest")
                ?.outcome

        test("2 回目も実行する(ビルドキャッシュから取らない・最新のままとしない)") {
            integrationTest() shouldBe TaskOutcome.SUCCESS
            integrationTest() shouldBe TaskOutcome.SUCCESS
            // 出力を消しても、キャッシュから戻さずに実行する
            File(project, "it/build").deleteRecursively()
            integrationTest() shouldBe TaskOutcome.SUCCESS
        }
    })
