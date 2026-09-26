package io.eia.tools.contract.rules

import io.eia.tools.contract.ContractDocument
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import io.eia.tools.contract.text
import kotlin.io.path.name

/** OpenAPI / AsyncAPI 共通: ファイル名は {name}.v{n}.yaml で、info.version のメジャーが n と一致する(Framework 5.3)。 */
internal fun checkContractFileName(document: ContractDocument): List<Violation> {
    val version =
        Naming.contractFileVersion(document.file.name)
            ?: return listOf(Violation(document.path, Rule.NAMING_CONTRACT_FILE, "ファイル名が {name}.v{n}.yaml ではありません"))
    val major =
        document.tree
            .get("info")
            ?.text("version")
            ?.substringBefore('.')
            ?.toIntOrNull()
    return if (major != null && major != version) {
        listOf(Violation(document.path, Rule.NAMING_CONTRACT_FILE, "info.version のメジャー($major)がファイル名の版(v$version)と一致しません"))
    } else {
        emptyList()
    }
}
