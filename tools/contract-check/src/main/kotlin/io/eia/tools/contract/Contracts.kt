package io.eia.tools.contract

import org.apache.avro.AvroRuntimeException
import org.apache.avro.Schema
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.yaml.YAMLMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/** YAML / JSON の契約ファイル。[path] はリポジトリルートからの相対パス。 */
class ContractDocument(
    val path: String,
    val file: Path,
    val tree: JsonNode,
)

/** Avro スキーマ(.avsc)。ファイルごとに独立して読む(名前付き型の参照は同じファイル内だけ)。 */
class AvroDocument(
    val path: String,
    val file: Path,
    val schema: Schema,
)

/** カタログの consumer / sender。[group] は Kafka の Consumer Group。 */
data class CatalogParty(
    val owner: String?,
    val system: String?,
    val group: String? = null,
)

/** カタログ 1 件のうち、検査に使う項目。形式の検査はカタログスキーマで行うため、ここでは欠落を許す。 */
data class CatalogEntry(
    val id: String?,
    val style: String?,
    val pattern: String?,
    val provider: CatalogParty?,
    val consumers: List<CatalogParty>,
    val contract: String?,
    val channels: List<String>,
)

class CatalogDocument(
    val path: String,
    val tree: JsonNode,
    val entry: CatalogEntry,
)

/**
 * リポジトリルート配下の contracts/ を読み込んだ結果。読めなかったファイルは [loadViolations] に入れ、以降の検査からは外す。
 */
data class Contracts(
    val root: Path,
    val openApis: List<ContractDocument>,
    val asyncApis: List<ContractDocument>,
    val avros: List<AvroDocument>,
    val catalogs: List<CatalogDocument>,
    val catalogSchema: ContractDocument?,
    val manifestSchemas: List<ContractDocument>,
    val fileSpecs: List<String>,
    val loadViolations: List<Violation>,
) {
    fun relative(file: Path): String = root.relativize(file).invariantSeparatorsPathString

    companion object {
        const val CONTRACTS_DIR = "contracts"
        const val CATALOG_SCHEMA = "contracts/catalog/catalog.schema.json"

        private val yaml = YAMLMapper.builder().build()
        private val json = JsonMapper.builder().build()

        fun load(root: Path): Contracts {
            val violations = mutableListOf<Violation>()
            val contracts = root.resolve(CONTRACTS_DIR)

            fun relative(file: Path) = root.relativize(file).invariantSeparatorsPathString

            fun readTree(file: Path): ContractDocument? =
                try {
                    val mapper = if (file.extension == "json" || file.extension == "avsc") json else yaml
                    ContractDocument(relative(file), file, mapper.readTree(file.toFile()))
                } catch (e: JacksonException) {
                    violations += Violation(relative(file), Rule.STRUCT_PARSE, e.originalMessage ?: e.message.orEmpty())
                    null
                }

            fun readAvro(file: Path): AvroDocument? =
                try {
                    AvroDocument(relative(file), file, Schema.Parser().parse(file.toFile()))
                } catch (e: AvroRuntimeException) {
                    violations += Violation(relative(file), Rule.STRUCT_AVRO, e.message.orEmpty())
                    null
                } catch (e: java.io.IOException) {
                    violations += Violation(relative(file), Rule.STRUCT_AVRO, e.message.orEmpty())
                    null
                }

            val catalogFiles = files(contracts.resolve("catalog")).filter { it.extension == "yaml" || it.extension == "yml" }
            val filesDir = contracts.resolve("files")
            return Contracts(
                root = root,
                openApis = files(contracts.resolve("openapi")).filter(::isYamlOrJson).mapNotNull(::readTree),
                asyncApis = files(contracts.resolve("asyncapi")).filter(::isYamlOrJson).mapNotNull(::readTree),
                avros = files(contracts.resolve("avro")).filter { it.extension == "avsc" }.mapNotNull(::readAvro),
                catalogs =
                    catalogFiles.mapNotNull(::readTree).map {
                        CatalogDocument(it.path, it.tree, parseCatalogEntry(it.tree))
                    },
                catalogSchema = root.resolve(CATALOG_SCHEMA).takeIf { it.isRegularFile() }?.let(::readTree),
                manifestSchemas =
                    files(filesDir)
                        .filter { it.name.startsWith("manifest.") && it.name.endsWith(".schema.json") }
                        .mapNotNull(::readTree),
                fileSpecs = files(filesDir).map { filesDir.relativize(it).invariantSeparatorsPathString },
                loadViolations = violations,
            )
        }

        /** [dir] 配下の通常ファイル(再帰。隠しファイルを除く)をパス順に返す。 */
        private fun files(dir: Path): List<Path> =
            if (!dir.isDirectory()) {
                emptyList()
            } else {
                Files.walk(dir).use { stream ->
                    stream
                        .asSequence()
                        .filter { it.isRegularFile() && !it.name.startsWith(".") }
                        .sorted()
                        .toList()
                }
            }

        private fun isYamlOrJson(file: Path) = file.extension in setOf("yaml", "yml", "json")

        private fun parseCatalogEntry(tree: JsonNode): CatalogEntry =
            CatalogEntry(
                id = tree.text("id"),
                style = tree.text("style"),
                pattern = tree.text("pattern"),
                provider = tree.get("provider")?.let(::parseParty),
                consumers =
                    tree
                        .get("consumers")
                        ?.values()
                        ?.map(::parseParty)
                        .orEmpty(),
                contract = tree.text("contract"),
                channels =
                    tree
                        .get("channels")
                        ?.values()
                        ?.mapNotNull { if (it.isString) it.stringValue() else null }
                        .orEmpty(),
            )

        private fun parseParty(node: JsonNode) = CatalogParty(node.text("owner"), node.text("system"), node.text("group"))
    }
}

/** 文字列の項目を返す。存在しない・文字列でない場合は null。 */
fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.stringValue()

/**
 * 同じ文書内の `$ref`(`#/...`)をたどった先のノードを返す。外部ファイルへの参照や解決できない参照はそのまま返す。
 */
fun JsonNode.resolveLocalRef(root: JsonNode): JsonNode =
    generateSequence(this) { node ->
        node
            .text("\$ref")
            ?.takeIf { it.startsWith("#/") }
            ?.let { root.at(it.substring(1)) }
            ?.takeUnless { it.isMissingNode }
    }.take(MAX_REF_DEPTH).last()

private const val MAX_REF_DEPTH = 16
