package io.eia.b2bgateway.adapters.spec

import io.eia.b2bgateway.application.port.outbound.EdiSpecs
import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.spec.DataFormat
import io.eia.b2bgateway.domain.spec.Direction
import io.eia.b2bgateway.domain.spec.EdiSpec
import io.eia.b2bgateway.domain.spec.ElementDefinition
import io.eia.b2bgateway.domain.spec.MessageIdentifier
import io.eia.b2bgateway.domain.spec.Qualifier
import io.eia.b2bgateway.domain.spec.RuleText
import io.eia.b2bgateway.domain.spec.SegmentDefinition
import io.eia.b2bgateway.domain.spec.Semantic
import io.eia.b2bgateway.domain.spec.Status
import io.eia.b2bgateway.domain.spec.StructureNode
import io.eia.b2bgateway.domain.spec.SyntaxRules
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper

/**
 * EDI のサブセットの仕様を、リソースに含めた契約の YAML(contracts/files/edi。ビルドで写す)から読む(ADR-0041 §7)。
 * 形と参照の食い違いは contract-check(CC-EDI-001・002)が CI で検査するので、ここでは読めない仕様を起動時の誤り(例外)にするだけにする。
 */
public class YamlEdiSpecs : EdiSpecs {
    override val orders: EdiSpec = load(ORDERS)
    override val contrl: EdiSpec = load(CONTRL)
    override val aperak: EdiSpec = load(APERAK)

    public companion object {
        public const val ORDERS: String = "edifact-orders.v1.yaml"
        public const val CONTRL: String = "edifact-contrl.v1.yaml"
        public const val APERAK: String = "edifact-aperak.v1.yaml"
        public const val RESOURCE_DIR: String = "/contracts/files/edi/"

        private val yaml: YAMLMapper = YAMLMapper.builder().build()

        /** リソースの仕様を読む。 */
        public fun load(file: String): EdiSpec {
            val resource = checkNotNull(YamlEdiSpecs::class.java.getResource(RESOURCE_DIR + file)) { "$RESOURCE_DIR$file がリソースにありません" }
            return parse(resource.readText())
        }

        /** 仕様の YAML の文字列を読む。 */
        public fun parse(text: String): EdiSpec {
            val tree = yaml.readTree(text)
            return EdiSpec(
                name = tree.text("name"),
                version = tree.required("version").asInt(),
                direction = Direction.valueOf(tree.text("direction").uppercase()),
                message =
                    tree
                        .required(
                            "message",
                        ).let { MessageIdentifier(it.text("type"), it.text("version"), it.text("release"), it.text("agency")) },
                syntax = syntax(tree.required("syntax")),
                segments = tree.required("segments").properties().associate { (tag, node) -> tag to segment(tag, node) },
                structure = tree.required("structure").values().map(::node),
                businessRules = rules(tree.get("businessRules")),
                errorTexts = rules(tree.get("errorTexts")),
            )
        }

        private fun syntax(node: JsonNode): SyntaxRules =
            SyntaxRules(
                versions =
                    node
                        .required("versions")
                        .values()
                        .map { it.asInt() }
                        .toSet(),
                characterSets =
                    node
                        .required("characterSets")
                        .values()
                        .map { checkNotNull(CharacterSet.of(it.asString())) { "知らない文字セット ${it.asString()}" } }
                        .toSet(),
                maxMessagesPerInterchange = node.required("maxMessagesPerInterchange").asInt(),
            )

        private fun segment(
            tag: String,
            node: JsonNode,
        ): SegmentDefinition = SegmentDefinition(tag, node.required("elements").values().map(::element))

        private fun element(node: JsonNode): ElementDefinition =
            node.get("components")?.let { components ->
                ElementDefinition.Composite(node.text("id"), status(node.text("status")), components.values().map(::simple))
            } ?: simple(node)

        private fun simple(node: JsonNode): ElementDefinition.Simple =
            ElementDefinition.Simple(
                id = node.text("id"),
                status = status(node.text("status")),
                format = checkNotNull(DataFormat.parse(node.text("format"))) { "形式 ${node.text("format")} を読めません" },
                codes =
                    node
                        .get("codes")
                        ?.values()
                        ?.map { it.text("value") }
                        ?.toSet()
                        .orEmpty(),
                semantic = node.get("semantic")?.let { Semantic.valueOf(it.asString().uppercase()) },
            )

        private fun node(node: JsonNode): StructureNode {
            val mandatory = node.text("status") == "M"
            val max = node.required("max").asInt()
            val qualifiers =
                node
                    .get(
                        "qualifiers",
                    )?.values()
                    ?.map { Qualifier(it.text("value"), it.text("status") == "M", it.required("max").asInt()) }
                    .orEmpty()
            return node.get("group")?.let { group ->
                StructureNode.GroupNode(group.asString(), mandatory, max, node.required("content").values().map(::node), qualifiers)
            } ?: StructureNode.SegmentNode(node.text("segment"), mandatory, max, qualifiers)
        }

        private fun rules(node: JsonNode?): List<RuleText> = node?.values()?.map { RuleText(it.text("code"), it.text("text")) }.orEmpty()

        private fun status(value: String): Status =
            when (value) {
                "M" -> Status.MANDATORY
                "C" -> Status.CONDITIONAL
                "X" -> Status.NOT_USED
                else -> error("知らない状態 $value")
            }
    }
}

private fun JsonNode.required(field: String): JsonNode = checkNotNull(get(field)) { "項目 $field がありません" }

// YAML の値は、引用符のない数字(例 version: 1)も文字列として読む
private fun JsonNode.text(field: String): String = required(field).asString()
