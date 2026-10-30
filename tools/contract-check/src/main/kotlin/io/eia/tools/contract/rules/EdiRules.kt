package io.eia.tools.contract.rules

import io.eia.tools.contract.ContractDocument
import io.eia.tools.contract.Contracts
import io.eia.tools.contract.JsonSchemas
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import io.eia.tools.contract.text
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import kotlin.io.path.name

/**
 * EDI のサブセットの仕様(contracts/files/edi/{name}.v{n}.yaml。ADR-0041)の検査と、main の版に対する不変(CC-COMPAT-005)。
 * 形はスキーマ(edi-spec.v{n}.schema.json)で、仕様の中の参照の食い違いはここで検査する。
 */
@Suppress("TooManyFunctions") // 参照の種類ごとに 1 つの関数にする
object EdiRules {
    /** どの仕様にも要るサービスセグメント(交換とメッセージの枠)。並び(structure)には書かない。 */
    private val SERVICE_SEGMENTS = setOf("UNB", "UNH", "UNT", "UNZ")
    private val SCHEMA_VERSION = Regex("^edi-spec\\.v([1-9][0-9]*)\\.schema\\.json$")
    private const val FILES_PREFIX = "contracts/files/"
    private const val EDI = "edi"
    private const val OUTBOUND = "outbound"
    private val FORMAT = Regex("^(a|n|an)(\\.\\.)?([1-9][0-9]*)$")

    /** 比べるときに除く注釈(説明の文と、データ要素・コードの名前)。最上位の name はファイル名との一致を CC-EDI-002 で検査する。 */
    private val ANNOTATIONS = setOf("description", "name")

    fun check(contracts: Contracts): List<Violation> =
        contracts.ediSpecSchemas.flatMap { document ->
            JsonSchemas.validateAsSchema(document.tree).map { Violation(document.path, Rule.STRUCT_JSON_SCHEMA, it) }
        } + checkSpecs(contracts) + checkRegistered(contracts)

    /** main の版の仕様を変えていない(CC-COMPAT-005)。消すことはライフサイクルの検査(CC-LIFECYCLE-003)が扱う。 */
    fun checkUnchanged(
        current: Contracts,
        baseline: Contracts,
    ): List<Violation> {
        val now = current.ediSpecs.associateBy { it.path }
        return baseline.ediSpecs.mapNotNull { old ->
            val new = now[old.path] ?: return@mapNotNull null
            if (withoutAnnotations(new.tree) == withoutAnnotations(old.tree)) {
                null
            } else {
                Violation(old.path, Rule.COMPAT_EDI, "EDI の仕様を変えました(説明の文と名前のほかの変更は、新しい版のファイルで行う)")
            }
        }
    }

    private fun checkSpecs(contracts: Contracts): List<Violation> {
        val schema =
            contracts.ediSpecSchemas
                .filter { JsonSchemas.validateAsSchema(it.tree).isEmpty() }
                .maxByOrNull {
                    SCHEMA_VERSION
                        .matchEntire(it.file.name)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt() ?: 0
                }
        if (schema == null) {
            return contracts.ediSpecs.map {
                Violation(it.path, Rule.EDI_SPEC, "EDI の仕様のスキーマ(contracts/files/edi-spec.v{n}.schema.json)がありません")
            }
        }
        val validator = JsonSchemas.schemaOf(schema.tree)
        val valid = mutableListOf<ContractDocument>()
        val violations =
            contracts.ediSpecs.flatMap { document ->
                JsonSchemas.validate(validator, document.tree).map { Violation(document.path, Rule.EDI_SPEC, it) }.also {
                    if (it.isEmpty()) valid += document
                }
            }
        val byPath = valid.associateBy { it.path }
        return violations +
            valid.flatMap { document ->
                consistency(document, byPath).map { Violation(document.path, Rule.EDI_SPEC_CONSISTENCY, it) }
            }
    }

    private fun consistency(
        document: ContractDocument,
        specs: Map<String, ContractDocument>,
    ): List<String> {
        val tree = document.tree
        val segments = tree.get("segments").properties().associate { (tag, node) -> tag to node }
        return checkName(document) +
            checkStructure(tree, segments) +
            checkMessage(tree, segments) +
            checkSyntax(tree, segments) +
            checkCodeFormats(segments) +
            checkAcknowledgements(tree, specs) +
            checkRuleCodes(tree, specs)
    }

    /** ファイル名の {name}.v{n} と、中の name・version が一致する。 */
    private fun checkName(document: ContractDocument): List<String> {
        val (name, version) = Naming.ediSpec(document.path.removePrefix(FILES_PREFIX)) ?: return emptyList()
        return buildList {
            if (document.tree.text("name") != name) add("name(${document.tree.text("name")})がファイル名と一致しません")
            if (document.tree.get("version")?.asInt() != version) add("version(${document.tree.get("version")?.asInt()})がファイル名と一致しません")
        }
    }

    /** 並びのセグメントは定義されていて、定義は並びかサービスセグメントで使われる。グループの最初は M のセグメントで、修飾子は codes の値。 */
    private fun checkStructure(
        tree: JsonNode,
        segments: Map<String, JsonNode>,
    ): List<String> {
        val problems = mutableListOf<String>()
        val used = mutableSetOf<String>()
        val groups = mutableListOf<String>()

        fun walk(nodes: JsonNode) {
            nodes.values().forEach { node ->
                val segment = node.text("segment")
                val group = node.text("group")
                if (segment != null) {
                    used += segment
                    if (segment !in segments) problems += "並びのセグメント '$segment' が segments に定義されていません"
                    problems += checkQualifiers("セグメント '$segment'", node, segments[segment])
                }
                if (group != null) {
                    groups += group
                    val first = node.get("content").values().firstOrNull()
                    val trigger = first?.text("segment")
                    if (trigger == null || first.text("status") != "M") {
                        problems += "グループ '$group' の最初が status M のセグメントではありません"
                    }
                    problems += checkQualifiers("グループ '$group'", node, trigger?.let(segments::get))
                    walk(node.get("content"))
                }
            }
        }
        walk(tree.get("structure"))
        (SERVICE_SEGMENTS - segments.keys).sorted().forEach { problems += "サービスセグメント '$it' が segments に定義されていません" }
        (SERVICE_SEGMENTS intersect used).sorted().forEach { problems += "サービスセグメント '$it' は並び(structure)に書きません" }
        (segments.keys - used - SERVICE_SEGMENTS).sorted().forEach { problems += "セグメント '$it' の定義が並びで使われていません" }
        groups
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
            .forEach { problems += "グループ '$it' が重複しています" }
        return problems
    }

    /** 修飾子は、セグメントの最初のデータ要素(複合なら最初の構成要素)の codes の値で、重複せず、M の数が max を超えない。 */
    private fun checkQualifiers(
        where: String,
        node: JsonNode,
        segment: JsonNode?,
    ): List<String> {
        val qualifiers = node.get("qualifiers")?.values().orEmpty()
        if (qualifiers.isEmpty() || segment == null) return emptyList()
        val first = segment.get("elements").values().first()
        val element = first.get("components")?.values()?.first() ?: first
        val codes = codes(element)
        val values = qualifiers.map { it.text("value") }
        val mandatory = qualifiers.count { it.text("status") == "M" }
        return buildList {
            values.filterNot { it in codes }.forEach { add("$where の修飾子 '$it' が最初のデータ要素(${element.text("id")})の codes にありません") }
            values
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .forEach { add("$where の修飾子 '$it' が重複しています") }
            if (mandatory > (node.get("max")?.asInt() ?: 0)) add("$where の必須の修飾子の数が max を超えています")
        }
    }

    /** UNH の S009 の codes が、message の型・版・リリース・機関のそれぞれ 1 つの値だけ。 */
    private fun checkMessage(
        tree: JsonNode,
        segments: Map<String, JsonNode>,
    ): List<String> {
        val unh = segments["UNH"] ?: return emptyList()
        val message = tree.get("message")
        val expected = mapOf("0065" to "type", "0052" to "version", "0054" to "release", "0051" to "agency")
        return expected.mapNotNull { (id, field) ->
            val codes = component(unh, "S009", id)?.let(::codes)
            val value = message.text(field)
            if (codes == listOf(value)) null else "UNH の S009 の $id の codes が message.$field('$value')だけになっていません"
        }
    }

    /** UNB の S001 の codes が、syntax の文字セットと構文の版と一致する。 */
    private fun checkSyntax(
        tree: JsonNode,
        segments: Map<String, JsonNode>,
    ): List<String> {
        val unb = segments["UNB"] ?: return emptyList()
        val syntax = tree.get("syntax")
        val characterSets =
            syntax
                .get("characterSets")
                .values()
                .map { it.stringValue() }
                .toSet()
        val versions =
            syntax
                .get("versions")
                .values()
                .map { it.asInt().toString() }
                .toSet()
        return buildList {
            if (component(unb, "S001", "0001")?.let(::codes)?.toSet() != characterSets) {
                add("UNB の S001 の 0001 の codes が syntax.characterSets と一致しません")
            }
            if (component(unb, "S001", "0002")?.let(::codes)?.toSet() != versions) {
                add("UNB の S001 の 0002 の codes が syntax.versions と一致しません")
            }
        }
    }

    /** コード値は、データ要素の format の種類(a = 英字、n = 数字)と長さに収まる。 */
    private fun checkCodeFormats(segments: Map<String, JsonNode>): List<String> =
        segments.flatMap { (tag, segment) ->
            segment
                .get("elements")
                .values()
                .flatMap { element -> element.get("components")?.values() ?: listOf(element) }
                .flatMap { element ->
                    val format = FORMAT.matchEntire(element.text("format").orEmpty()) ?: return@flatMap emptyList()
                    val (type, range, length) = format.destructured
                    codes(element).filterNotNull().filterNot { fits(it, type, range.isNotEmpty(), length.toInt()) }.map {
                        "$tag の ${element.text("id")} のコード値 '$it' が format(${element.text("format")})に収まりません"
                    }
                }
        }

    private fun fits(
        value: String,
        type: String,
        upTo: Boolean,
        length: Int,
    ): Boolean {
        val kind =
            when (type) {
                "a" -> value.none { it.isDigit() }
                "n" -> value.all { it.isDigit() }
                else -> true
            }
        return kind && if (upTo) value.length <= length else value.length == length
    }

    /** inbound の応答の仕様は、outbound の EDI の仕様を指す。 */
    private fun checkAcknowledgements(
        tree: JsonNode,
        specs: Map<String, ContractDocument>,
    ): List<String> =
        listOf("syntax", "business").mapNotNull { kind ->
            val path = tree.get("acknowledgements")?.text(kind) ?: return@mapNotNull null
            val target = specs[path]
            when {
                target == null -> "acknowledgements.$kind の '$path' が(スキーマに適合する)EDI の仕様にありません"
                target.tree.text("direction") != OUTBOUND -> "acknowledgements.$kind の '$path' が outbound の仕様ではありません"
                else -> null
            }
        }

    /** 業務の規則のコード(APERAK の 9321)は、規則と、業務の応答の仕様の errorTexts を合わせて一意。 */
    private fun checkRuleCodes(
        tree: JsonNode,
        specs: Map<String, ContractDocument>,
    ): List<String> {
        val business =
            tree
                .get("acknowledgements")
                ?.text("business")
                ?.let(specs::get)
                ?.tree
        val codes =
            listOf(tree.get("businessRules"), tree.get("errorTexts"), business?.get("errorTexts"))
                .flatMap { it?.values().orEmpty() }
                .mapNotNull { it.text("code") }
        return codes
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
            .map { "業務の規則のコード '$it' が重複しています(businessRules と、業務の応答の仕様の errorTexts を合わせて一意にする)" }
    }

    /** EDI の仕様は style: edi のカタログに登録され、style: edi のカタログは EDI の仕様を contract にする(CC-EDI-003)。 */
    private fun checkRegistered(contracts: Contracts): List<Violation> {
        val specs = contracts.ediSpecs.map { it.path }.toSet()
        val ediCatalogs = contracts.catalogs.filter { it.entry.style == EDI }
        val registered = ediCatalogs.mapNotNull { it.entry.contract }.toSet()
        return contracts.ediSpecs
            .filter { it.path !in registered }
            .map { Violation(it.path, Rule.EDI_SPEC_UNREGISTERED, "この仕様を contract に指定した style: edi のカタログがありません") } +
            ediCatalogs
                .filter { it.entry.contract !in specs }
                .map {
                    Violation(
                        it.path,
                        Rule.EDI_SPEC_UNREGISTERED,
                        "style: edi なのに、contract '${it.entry.contract}' が ${Contracts.EDI_DIR} の EDI の仕様ではありません",
                    )
                }
    }

    private fun component(
        segment: JsonNode,
        composite: String,
        id: String,
    ): JsonNode? =
        segment
            .get("elements")
            .values()
            .firstOrNull { it.text("id") == composite }
            ?.get("components")
            ?.values()
            ?.firstOrNull { it.text("id") == id }

    private fun codes(element: JsonNode): List<String?> =
        element
            .get("codes")
            ?.values()
            .orEmpty()
            .map { it.text("value") }

    private fun withoutAnnotations(node: JsonNode): JsonNode =
        when (node) {
            is ObjectNode -> {
                val copy = node.deepCopy()
                // segments の下のキーはセグメントのタグ(項目の名前)なので、注釈のキーと同じ名前でも除かない(タグは英大文字 3 文字で重ならない)
                copy.remove(ANNOTATIONS)
                copy.properties().toList().forEach { (name, child) -> copy.set(name, withoutAnnotations(child)) }
                copy
            }

            is ArrayNode -> {
                val copy = node.deepCopy()
                for (index in 0 until copy.size()) copy.set(index, withoutAnnotations(copy.get(index)))
                copy
            }

            else -> {
                node
            }
        }
}
