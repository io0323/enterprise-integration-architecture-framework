package io.eia.buildlogic

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File

private val repositoryRoot = File(checkNotNull(System.getProperty("eia.repositoryRoot")) { "Gradle の test タスクから実行してください" })

/**
 * `verifyNoTestSupportInMain`(eia.jvm-library。ADR-0016 §5)を、実際の plugin を当てた小さなビルドで確かめる(Gradle TestKit。#31)。
 *
 * - `:lib` は main に `:platform:test-support` を直接、`:app` は `:lib` を通して間接に依存する(どちらも違反)。
 * - `:ok` はテストだけで依存する(違反ではない)。
 */
class VerifyNoTestSupportInMainSpec :
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
            rootProject.name = "guard-fixture"
            include(":platform:test-support", ":lib", ":app", ":ok")
            """,
        )
        write("build.gradle.kts", "")
        listOf("platform/test-support", "lib", "app", "ok").forEach { dir ->
            val dependencies =
                when (dir) {
                    "lib" -> """implementation(project(":platform:test-support"))"""
                    "app" -> """implementation(project(":lib"))"""
                    "ok" -> """testImplementation(project(":platform:test-support"))"""
                    else -> ""
                }
            write("$dir/build.gradle.kts", "plugins { id(\"eia.jvm-library\") }\ndependencies { $dependencies }\n")
        }

        fun runner(vararg tasks: String) =
            GradleRunner
                .create()
                .withProjectDir(project)
                .withPluginClasspath()
                .withArguments(*tasks, "--stacktrace")

        test("main が test-support に直接依存すれば失敗し、経路を示す") {
            val result = runner(":lib:verifyNoTestSupportInMain").buildAndFail()
            result.output shouldContain ":lib の compileClasspath に :platform:test-support が含まれています(:lib -> :platform:test-support)"
        }

        test("推移的な依存でも失敗し、間のプロジェクトを経路に含める") {
            val result = runner(":app:verifyNoTestSupportInMain").buildAndFail()
            result.output shouldContain "(:app -> :lib -> :platform:test-support)"
        }

        test("テストだけの依存なら成功する") {
            runner(":ok:verifyNoTestSupportInMain").build().task(":ok:verifyNoTestSupportInMain")?.outcome shouldBe TaskOutcome.SUCCESS
        }

        test("check から実行される(build の品質ゲートに入る)") {
            runner(":ok:check", "--dry-run").build().output shouldContain ":ok:verifyNoTestSupportInMain SKIPPED"
        }
    })
