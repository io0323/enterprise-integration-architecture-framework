package io.eia.buildlogic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.lang.reflect.Proxy

/** 依存グラフの 1 つの節(Gradle の解決結果の代わり)。依存は後から足す(循環を作れるように)。 */
private class Node(
    val id: ComponentIdentifier,
) {
    val dependsOn = mutableListOf<Node>()

    val result: ResolvedComponentResult by lazy {
        proxy(ResolvedComponentResult::class.java) { name ->
            when (name) {
                "getId" -> id
                "getDependencies" -> dependsOn.map { it.edge }.toSet()
                else -> error("使わない: $name")
            }
        }
    }

    private val edge: DependencyResult by lazy {
        proxy(ResolvedDependencyResult::class.java) { name ->
            when (name) {
                "getSelected" -> result
                else -> error("使わない: $name")
            }
        }
    }
}

private fun <T> proxy(
    type: Class<T>,
    answer: (String) -> Any?,
): T =
    type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "equals" -> self === args?.get(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "proxy(${type.simpleName})"
                else -> answer(method.name)
            }
        },
    )

private fun project(path: String) =
    Node(
        proxy(ProjectComponentIdentifier::class.java) { name ->
            when (name) {
                "getProjectPath" -> path
                "getDisplayName" -> "project $path"
                else -> error("使わない: $name")
            }
        },
    )

private fun module(coordinates: String) =
    Node(
        proxy(ModuleComponentIdentifier::class.java) { name ->
            when (name) {
                "getDisplayName" -> coordinates
                else -> error("使わない: $name")
            }
        },
    )

/** `findProjectPath`(`verifyNoTestSupportInMain` の経路の探索。ADR-0016 §5)の単体テスト(#31)。 */
class TestSupportGuardSpec :
    FunSpec({
        test("直接の依存: 経路はルートから test-support まで") {
            val root = project(":services:order:adapters")
            root.dependsOn += project(TEST_SUPPORT_PROJECT)
            findProjectPath(root.result, TEST_SUPPORT_PROJECT) shouldBe listOf(":services:order:adapters", TEST_SUPPORT_PROJECT)
        }

        test("推移的な依存: 間のプロジェクトとライブラリを経路に含める") {
            val root = project(":services:order:app")
            val adapters = project(":services:order:adapters")
            val library = module("org.example:lib:1.0")
            root.dependsOn += listOf(module("org.jetbrains.kotlin:kotlin-stdlib:2.4.20"), adapters)
            adapters.dependsOn += library
            library.dependsOn += project(TEST_SUPPORT_PROJECT)
            findProjectPath(root.result, TEST_SUPPORT_PROJECT) shouldBe
                listOf(":services:order:app", ":services:order:adapters", "org.example:lib:1.0", TEST_SUPPORT_PROJECT)
        }

        test("含まれなければ null。ルート自身は数えない") {
            val root = project(TEST_SUPPORT_PROJECT)
            root.dependsOn += project(":shared:kernel")
            findProjectPath(root.result, TEST_SUPPORT_PROJECT) shouldBe null
        }

        test("循環のあるグラフでも止まる") {
            val root = project(":a")
            val b = project(":b")
            val c = project(":c")
            root.dependsOn += b
            b.dependsOn += c
            c.dependsOn += b
            findProjectPath(root.result, TEST_SUPPORT_PROJECT) shouldBe null
            c.dependsOn += project(TEST_SUPPORT_PROJECT)
            findProjectPath(root.result, TEST_SUPPORT_PROJECT) shouldBe listOf(":a", ":b", ":c", TEST_SUPPORT_PROJECT)
        }
    })
