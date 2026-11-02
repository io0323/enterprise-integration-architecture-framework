package io.eia.b2bgateway.application

import io.eia.b2bgateway.application.port.outbound.EdiSpecs
import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.order.BusinessRule
import io.eia.b2bgateway.domain.spec.DataFormat
import io.eia.b2bgateway.domain.spec.Direction
import io.eia.b2bgateway.domain.spec.EdiSpec
import io.eia.b2bgateway.domain.spec.ElementDefinition
import io.eia.b2bgateway.domain.spec.MessageIdentifier
import io.eia.b2bgateway.domain.spec.RuleText
import io.eia.b2bgateway.domain.spec.SegmentDefinition
import io.eia.b2bgateway.domain.spec.Status
import io.eia.b2bgateway.domain.spec.StructureNode
import io.eia.b2bgateway.domain.spec.SyntaxRules
import io.eia.shared.kernel.err

/** どの値も受け付けるデータ要素(位置で読むだけ。値の検査は domain と adapters のテストで行う)。 */
private fun loose(tag: String): SegmentDefinition =
    SegmentDefinition(
        tag,
        // UNB の 11 個まで(試験の印 0035 の位置)
        List(11) { index ->
            ElementDefinition.Composite(
                "C${index.toString().padStart(3, '0')}",
                Status.CONDITIONAL,
                List(4) { ElementDefinition.Simple("000$it", Status.CONDITIONAL, checkNotNull(DataFormat.parse("an..35"))) },
            )
        },
    )

private fun spec(
    type: String,
    release: String,
    structure: List<StructureNode> = emptyList(),
    tags: List<String> = emptyList(),
    rules: List<RuleText> = emptyList(),
): EdiSpec =
    EdiSpec(
        name = "test-$type",
        version = 1,
        direction = Direction.INBOUND,
        message = MessageIdentifier(type, "D", release, "UN"),
        syntax = SyntaxRules(setOf(3), setOf(CharacterSet.UNOA, CharacterSet.UNOC), 10),
        segments = (listOf("UNB", "UNH", "UNT", "UNZ") + tags).associateWith(::loose),
        structure = structure,
        businessRules = rules,
        errorTexts = listOf(RuleText("APR901", "ORDER NOT ACCEPTED BY SUPPLIER SYSTEM")),
    )

private fun segment(
    tag: String,
    max: Int = 1,
    mandatory: Boolean = true,
) = StructureNode.SegmentNode(tag, mandatory, max)

/** テスト用の仕様(業務の規則のコードは BusinessRule と同じ。APERAK の固定の文言 APR901 を持つ)。 */
internal object TestEdiSpecs : EdiSpecs {
    override val orders: EdiSpec =
        spec(
            "ORDERS",
            "96A",
            structure =
                listOf(
                    segment("BGM"),
                    segment("DTM", max = 2),
                    StructureNode.GroupNode("SG2", true, 3, listOf(segment("NAD"))),
                    segment("CUX"),
                    StructureNode.GroupNode(
                        "SG25",
                        true,
                        99,
                        listOf(segment("LIN"), segment("PIA", mandatory = false), segment("QTY"), segment("PRI")),
                    ),
                    segment("CNT", mandatory = false),
                ),
            tags = listOf("BGM", "DTM", "NAD", "CUX", "LIN", "PIA", "QTY", "PRI", "CNT"),
            rules = BusinessRule.entries.map { RuleText(it.code, it.name.replace('_', ' ')) },
        )
    override val contrl: EdiSpec = spec("CONTRL", "3")
    override val aperak: EdiSpec = spec("APERAK", "96A")
}
