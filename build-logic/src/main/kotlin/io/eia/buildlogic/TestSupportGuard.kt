package io.eia.buildlogic

import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/** テスト専用のモジュール(ADR-0016 §5)。main のクラスパスに載せてはならない。 */
const val TEST_SUPPORT_PROJECT: String = ":platform:test-support"

/**
 * 解決済みの依存グラフに [projectPath] のプロジェクトが(推移的な依存を含めて)含まれていれば、そこまでの経路を返す。
 * 含まれていなければ null。
 */
fun findProjectPath(
    root: ResolvedComponentResult,
    projectPath: String,
): List<String>? {
    val visited = mutableSetOf<ResolvedComponentResult>()

    fun search(component: ResolvedComponentResult): List<String>? {
        if (!visited.add(component)) return null
        val id = component.id
        val label = if (id is ProjectComponentIdentifier) id.projectPath else id.displayName
        if (component !== root && id is ProjectComponentIdentifier && id.projectPath == projectPath) return listOf(label)
        return component.dependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .firstNotNullOfOrNull { search(it.selected) }
            ?.let { listOf(label) + it }
    }

    return search(root)
}
