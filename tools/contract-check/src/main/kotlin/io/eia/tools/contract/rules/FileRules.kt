package io.eia.tools.contract.rules

import io.eia.tools.contract.ContractDocument
import io.eia.tools.contract.Contracts
import io.eia.tools.contract.JsonSchemas
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import tools.jackson.databind.JsonNode
import kotlin.io.path.name

/** ファイル連携の契約(contracts/files)の検査。Framework 9、INTEGRATION_STANDARDS §1・§2。 */
object FileRules {
    /** manifest の必須項目(INTEGRATION_STANDARDS §2)。 */
    val MANIFEST_REQUIRED = listOf("file", "recordCount", "sha256", "schemaVersion", "createdAt", "traceparent", "correlationId")
    private const val FILES_DIR = "contracts/files"
    private val FILE_SPEC_NAME = Regex("^([a-z][a-z0-9-]*)_([a-z][a-z0-9-]*)\\.v([1-9][0-9]*)\\.yaml$")
    private val SCHEMA_VERSION = Regex("^file-spec\\.v([1-9][0-9]*)\\.schema\\.json$")

    fun check(contracts: Contracts): List<Violation> =
        checkNaming(contracts) +
            checkManifestSchemas(contracts) +
            checkFileSpecSchemas(contracts) +
            checkFileSpecs(contracts) +
            checkUnregistered(contracts)

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

    private fun checkFileSpecSchemas(contracts: Contracts): List<Violation> =
        contracts.fileSpecSchemas.flatMap { document ->
            JsonSchemas.validateAsSchema(document.tree).map { Violation(document.path, Rule.STRUCT_JSON_SCHEMA, it) }
        }

    /** 最も新しい版のファイルの仕様のスキーマ(メタスキーマに適合しないものは使わない)。 */
    private fun latestSchema(contracts: Contracts): ContractDocument? =
        contracts.fileSpecSchemas
            .filter { JsonSchemas.validateAsSchema(it.tree).isEmpty() }
            .maxByOrNull {
                SCHEMA_VERSION
                    .matchEntire(it.file.name)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: 0
            }

    private fun checkFileSpecs(contracts: Contracts): List<Violation> {
        val schema = latestSchema(contracts)
        if (schema == null) {
            return contracts.fileSpecDocuments.map {
                Violation(it.path, Rule.FILE_SPEC, "ファイルの仕様のスキーマ($FILES_DIR/file-spec.v{n}.schema.json)がありません")
            }
        }
        val validator = JsonSchemas.schemaOf(schema.tree)
        return contracts.fileSpecDocuments.flatMap { document ->
            val errors = JsonSchemas.validate(validator, document.tree).map { Violation(document.path, Rule.FILE_SPEC, it) }
            errors + if (errors.isEmpty()) checkSpecName(document) + checkSpecColumns(document) else emptyList()
        }
    }

    /** ファイル名の {system}_{dataset}.v{n} と、中の system・dataset・version が一致する。 */
    private fun checkSpecName(document: ContractDocument): List<Violation> {
        val (system, dataset, version) = FILE_SPEC_NAME.matchEntire(document.file.name)?.destructured ?: return emptyList()
        val tree = document.tree
        val mismatches =
            buildList {
                if (tree.get("system")?.stringValue() != system) add("system(${tree.get("system")?.stringValue()})")
                if (tree.get("dataset")?.stringValue() != dataset) add("dataset(${tree.get("dataset")?.stringValue()})")
                if (tree.get("version")?.asInt() != version.toInt()) add("version(${tree.get("version")?.asInt()})")
            }
        return if (mismatches.isEmpty()) {
            emptyList()
        } else {
            listOf(Violation(document.path, Rule.FILE_SPEC_NAME, "${mismatches.joinToString()} がファイル名と一致しません"))
        }
    }

    /** 列の名前は一意で、orderBy・key は存在する列を指す。 */
    private fun checkSpecColumns(document: ContractDocument): List<Violation> {
        val names = columns(document.tree).map { it.name }
        val duplicated =
            names
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        val unknown =
            listOf("orderBy", "key").flatMap { field ->
                document.tree
                    .get(field)
                    ?.values()
                    .orEmpty()
                    .map { it.stringValue() }
                    .filterNot { it in names }
                    .map { "$field の '$it'" }
            }
        return duplicated.map { Violation(document.path, Rule.FILE_SPEC, "列 '$it' が重複しています") } +
            unknown.map { Violation(document.path, Rule.FILE_SPEC, "$it は列にありません") }
    }

    private fun checkUnregistered(contracts: Contracts): List<Violation> {
        val registered = contracts.catalogs.mapNotNull { it.entry.contract }.toSet()
        return contracts.fileSpecDocuments
            .filter { it.path !in registered }
            .map { Violation(it.path, Rule.FILE_SPEC_UNREGISTERED, "この仕様を contract に指定したカタログがありません") }
    }

    internal data class Column(
        val name: String,
        val type: String?,
        val nullable: Boolean?,
        val precision: Int?,
        val scale: Int?,
        val enumValues: List<String>?,
    )

    internal fun columns(tree: JsonNode): List<Column> =
        tree.get("columns")?.values().orEmpty().map { column ->
            Column(
                name = column.get("name")?.stringValue().orEmpty(),
                type = column.get("type")?.stringValue(),
                nullable = column.get("nullable")?.asBoolean(),
                precision = column.get("precision")?.asInt(),
                scale = column.get("scale")?.asInt(),
                enumValues = column.get("enum")?.values()?.map { it.stringValue() },
            )
        }
}
