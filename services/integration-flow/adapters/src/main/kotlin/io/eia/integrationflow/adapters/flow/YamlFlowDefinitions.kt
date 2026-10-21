package io.eia.integrationflow.adapters.flow

import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.eia.integrationflow.adapters.canonical.CanonicalTypes
import io.eia.integrationflow.application.port.outbound.FlowDefinitions
import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.MappingNode
import io.eia.integrationflow.domain.MappingRule
import io.eia.integrationflow.domain.Ordering
import io.eia.integrationflow.domain.Route
import io.eia.integrationflow.domain.SourcePath
import io.eia.integrationflow.domain.Transform
import io.eia.integrationflow.domain.Trigger
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.util.Locale
import kotlin.time.Duration

/**
 * [FlowDefinitions] の実装。Flow の定義(YAML)を読み、起動時に確かめる(誤りがあれば起動しない。ADR-0036 §1・ROADMAP P10 の DoD)。
 *
 * 1. YAML として読めること。
 * 2. JSON Schema(contracts/flows/flow.v1.schema.json。イメージに含めたもの)への適合。式・スクリプトは形として書けない。
 * 3. ファイル名が `{id}.v{version}.flow.yaml` であること。
 * 4. 形では表せない整合(振り分けの形・lookup の対応表。[FlowDefinition.of])。
 * 5. mapping・ordering.key・route の field が Canonical の項目で、Canonical の必須の項目をすべて書いていること。
 *
 * contract-check(CC-FLOW-001・002)も PR の時点で同じことを確かめる。カタログの参照(連携・トピック)は contract-check だけが確かめる
 * (実行時にはカタログを持たない)。誤りは、すべてのファイルの分をまとめて返す。
 */
public class YamlFlowDefinitions(
    private val source: FlowSource = FlowSource.Classpath,
) : FlowDefinitions {
    @Suppress("ReturnCount") // 読めない・空・ファイルごとの結果のそれぞれで返す
    override fun load(): Result<List<FlowDefinition>, ValidationError> {
        val files =
            when (val read = source.files()) {
                is FlowSource.Result.Unreadable -> return err(
                    ValidationError(
                        read.problems.map { (name, reason) ->
                            FieldViolation(name, reason)
                        },
                    ),
                )

                is FlowSource.Result.Files -> read.files
            }
        if (files.isEmpty()) return err(ValidationError.of("flows", "Flow の定義がありません"))
        val results = files.map { file -> file to load(file) }
        val violations = results.flatMap { (_, result) -> (result as? Result.Err)?.error?.violations.orEmpty() }
        return if (violations.isEmpty()) ok(results.map { (it.second as Result.Ok).value }) else err(ValidationError(violations))
    }

    @Suppress("ReturnCount") // 検証の段ごとに、最初の誤りで止める(後の段は前の段の形を前提にする)
    private fun load(file: FlowFile): Result<FlowDefinition, ValidationError> {
        fun failed(reasons: List<String>) = err(ValidationError(reasons.map { FieldViolation(file.name, it) }))
        val tree =
            try {
                yaml.readTree(file.text)
            } catch (e: JacksonException) {
                return failed(listOf("YAML として読めません(${e::class.simpleName})"))
            }
        val schemaErrors = schema.validate(tree).map { "${it.instanceLocation}: ${it.message}" }
        if (schemaErrors.isNotEmpty()) return failed(schemaErrors)
        val expected = "${tree.text("id")}.v${tree.get("version").asInt()}${FlowSource.SUFFIX}"
        if (file.name != expected) return failed(listOf("ファイル名は $expected にします(id と version から)"))
        val canonicalErrors = canonicalProblems(tree)
        if (canonicalErrors.isNotEmpty()) return failed(canonicalErrors)
        return when (val definition = definition(tree)) {
            is Result.Ok -> {
                definition
            }

            is Result.Err -> {
                err(
                    ValidationError(definition.error.violations.map { FieldViolation(file.name, "${it.field}: ${it.reason}") }),
                )
            }
        }
    }

    /** スキーマに適合した木から、domain の定義を作る(場所の形はスキーマで確かめ済み)。 */
    private fun definition(tree: JsonNode): Result<FlowDefinition, ValidationError> =
        FlowDefinition.of(
            id = checkNotNull(tree.text("id")),
            version = tree.get("version").asInt(),
            integration = checkNotNull(tree.text("integration")),
            canonical = checkNotNull(tree.text("canonical")),
            triggers = tree.get("triggers").values().map(::trigger),
            ordering = Ordering(checkNotNull(tree.get("ordering").text("key")), path(tree.get("ordering").text("version"))),
            mapping = mapping(tree.get("mapping")),
            tables =
                tree
                    .get("tables")
                    ?.properties()
                    ?.associate { (name, table) -> name to table.properties().associate { (k, v) -> k to v.stringValue() } }
                    .orEmpty(),
            routes =
                tree.get("routes").values().map { route ->
                    val condition = route.get("when")?.let { checkNotNull(it.text("field")) to checkNotNull(it.text("equals")) }
                    Route(condition, checkNotNull(route.text("deliver")))
                },
        )

    private fun trigger(node: JsonNode): Trigger =
        when (node.text("kind")) {
            "event" -> {
                Trigger.Event(
                    topic = checkNotNull(node.text("topic")),
                    source = checkNotNull(node.text("source")),
                    eventTypes =
                        node
                            .get("eventTypes")
                            .values()
                            .map { it.stringValue() }
                            .toSet(),
                    record = path(node.text("record")),
                )
            }

            else -> {
                Trigger.Poll(
                    connector = checkNotNull(node.text("connector")),
                    resource = checkNotNull(node.text("resource")),
                    every = Duration.parseIsoString(checkNotNull(node.text("every"))),
                )
            }
        }

    private fun mapping(node: JsonNode): Map<String, MappingNode> =
        node.properties().associate { (name, value) ->
            name to
                if (value.has("fields")) {
                    MappingNode.Nested(value.text("when")?.let(::path), mapping(value.get("fields")))
                } else {
                    MappingNode.Field(rule(value))
                }
        }

    private fun rule(node: JsonNode): MappingRule {
        val transform =
            when (val name = node.text("transform") ?: "copy") {
                "copy" -> Transform.Copy
                "trim" -> Transform.Trim
                "lowercase" -> Transform.Lowercase
                "uppercase" -> Transform.Uppercase
                "iso-instant" -> Transform.IsoInstant
                "epoch-millis-instant" -> Transform.EpochMillisInstant
                "lookup" -> Transform.Lookup(checkNotNull(node.text("table")))
                "constant" -> Transform.Constant(checkNotNull(node.text("value")))
                else -> error("スキーマにない変換です: $name")
            }
        return MappingRule(node.text("from")?.let(::path), transform)
    }

    /** mapping・ordering.key・route の field が Canonical の項目で、必須の項目をすべて書いている(contract-check の CC-FLOW-002 と同じ)。 */
    private fun canonicalProblems(tree: JsonNode): List<String> {
        val canonical = tree.text("canonical")
        val descriptor = CanonicalTypes.descriptors[canonical] ?: return listOf("Canonical の型 '$canonical' を扱えません")
        val problems = mutableListOf<String>()
        mappingProblems(tree.get("mapping"), descriptor, "", problems)
        val fields =
            listOfNotNull(tree.get("ordering").text("key")) + tree.get("routes").values().mapNotNull { it.get("when")?.text("field") }
        fields.filterNot { leafExists(descriptor, it) }.forEach { problems += "Canonical の $canonical に項目 '$it' がありません" }
        return problems
    }

    private fun mappingProblems(
        mapping: JsonNode?,
        descriptor: SerialDescriptor,
        prefix: String,
        problems: MutableList<String>,
    ) {
        val names = (0 until descriptor.elementsCount).associateBy { descriptor.getElementName(it) }
        val mapped = mapping?.propertyNames()?.toSet().orEmpty()
        mapped.filterNot { it in names }.forEach { problems += "Canonical に項目 '$prefix$it' がありません" }
        names.forEach { (name, index) ->
            val element = descriptor.getElementDescriptor(index)
            val required = !descriptor.isElementOptional(index) && !element.isNullable
            val node = mapping?.get(name)
            when {
                node == null && required -> {
                    problems += "Canonical の必須の項目 '$prefix$name' が mapping にありません"
                }

                node != null && node.has("fields") -> {
                    mappingProblems(node.get("fields"), element, "$prefix$name.", problems)
                }

                node != null && element.kind == StructureKind.CLASS && !element.isInline -> {
                    problems +=
                        "'$prefix$name' は入れ子の型です。fields で項目ごとに書きます"
                }
            }
        }
    }

    private fun leafExists(
        descriptor: SerialDescriptor,
        path: String,
    ): Boolean {
        val head = path.substringBefore('.')
        val index = (0 until descriptor.elementsCount).firstOrNull { descriptor.getElementName(it) == head } ?: return false
        return if ('.' in path) leafExists(descriptor.getElementDescriptor(index), path.substringAfter('.')) else true
    }

    private fun path(text: String?): SourcePath = checkNotNull(text?.let(SourcePath::parse)) { "スキーマで確かめた場所の形です: $text" }

    private companion object {
        const val SCHEMA = "/contracts/flows/flow.v1.schema.json"
        val yaml: YAMLMapper = YAMLMapper.builder().build()

        // メッセージの言語を実行環境のロケールに依存させない(contract-check と同じ)
        val schema: Schema by lazy {
            val registry =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
                    builder.schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.JAPANESE).build())
                }
            val text = checkNotNull(YamlFlowDefinitions::class.java.getResource(SCHEMA)) { "$SCHEMA がリソースにありません" }.readText()
            registry.getSchema(yaml.readTree(text))
        }

        fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.stringValue()
    }
}
