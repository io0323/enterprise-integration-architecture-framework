package io.eia.tools.contract.rules

import io.eia.tools.contract.Contracts
import io.eia.tools.contract.JsonSchemas
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation

/** ファイル連携の契約(contracts/files)の検査。Framework 9、INTEGRATION_STANDARDS §1・§2。 */
object FileRules {
    /** manifest の必須項目(INTEGRATION_STANDARDS §2)。 */
    val MANIFEST_REQUIRED = listOf("file", "recordCount", "sha256", "schemaVersion", "createdAt", "traceparent", "correlationId")
    private const val FILES_DIR = "contracts/files"

    fun check(contracts: Contracts): List<Violation> = checkNaming(contracts) + checkManifestSchemas(contracts)

    private fun checkNaming(contracts: Contracts): List<Violation> =
        contracts.fileSpecs.mapNotNull { relative ->
            Naming.checkFileSpec(relative)?.let { (rule, message) -> Violation("$FILES_DIR/$relative", rule, message) }
        }

    private fun checkManifestSchemas(contracts: Contracts): List<Violation> =
        contracts.manifestSchemas.flatMap { document ->
            val metaErrors = JsonSchemas.validateAsSchema(document.tree)
            if (metaErrors.isNotEmpty()) {
                return@flatMap metaErrors.map { Violation(document.path, Rule.STRUCT_JSON_SCHEMA, it) }
            }
            val required =
                document.tree
                    .get("required")
                    ?.values()
                    ?.mapNotNull { if (it.isString) it.stringValue() else null }
                    .orEmpty()
                    .toSet()
            val missing = MANIFEST_REQUIRED.filterNot { it in required }
            if (missing.isEmpty()) {
                emptyList()
            } else {
                listOf(Violation(document.path, Rule.FILE_MANIFEST, "required に ${missing.joinToString()} がありません"))
            }
        }
}
