package io.eia.b2bgateway.domain

import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.spec.DataFormat
import io.eia.b2bgateway.domain.spec.Direction
import io.eia.b2bgateway.domain.spec.EdiSpec
import io.eia.b2bgateway.domain.spec.ElementDefinition
import io.eia.b2bgateway.domain.spec.MessageIdentifier
import io.eia.b2bgateway.domain.spec.Qualifier
import io.eia.b2bgateway.domain.spec.SegmentDefinition
import io.eia.b2bgateway.domain.spec.Semantic
import io.eia.b2bgateway.domain.spec.Status
import io.eia.b2bgateway.domain.spec.StructureNode
import io.eia.b2bgateway.domain.spec.SyntaxRules

/**
 * domain のテスト用の小さな仕様(契約の仕様と同じ形。実際の契約での検査は adapters のテストで行う)。
 * 並び: BGM(M1)・DTM(M2。137 は M1、2 は C1)・SG1(NAD。M2。BY は M1、SU は C1)の中に LOC(C1)・QTY(C1)。
 */
internal object TestSpecs {
    private val M = Status.MANDATORY
    private val C = Status.CONDITIONAL
    private val X = Status.NOT_USED

    fun simple(
        id: String,
        status: Status,
        format: String,
        codes: Set<String> = emptySet(),
        semantic: Semantic? = null,
    ): ElementDefinition.Simple = ElementDefinition.Simple(id, status, checkNotNull(DataFormat.parse(format)), codes, semantic)

    fun composite(
        id: String,
        status: Status,
        vararg components: ElementDefinition.Simple,
    ): ElementDefinition.Composite = ElementDefinition.Composite(id, status, components.toList())

    private val unb =
        SegmentDefinition(
            "UNB",
            listOf(
                composite("S001", M, simple("0001", M, "a4", setOf("UNOA", "UNOB", "UNOC")), simple("0002", M, "n1", setOf("3"))),
                composite("S002", M, simple("0004", M, "an..35"), simple("0007", M, "an..4"), simple("0008", X, "an..14")),
                composite("S003", M, simple("0010", M, "an..35"), simple("0007", M, "an..4"), simple("0014", X, "an..14")),
                composite("S004", M, simple("0017", M, "n6"), simple("0019", M, "n4", semantic = Semantic.TIME)),
                simple("0020", M, "an..14"),
                composite("S005", X, simple("0022", X, "an..14")),
                simple("0026", X, "an..14"),
                simple("0029", X, "a1"),
                simple("0031", C, "n1", setOf("1")),
                simple("0032", X, "an..35"),
                simple("0035", C, "n1", setOf("1")),
            ),
        )

    private val unh =
        SegmentDefinition(
            "UNH",
            listOf(
                simple("0062", M, "an..14"),
                composite(
                    "S009",
                    M,
                    simple("0065", M, "an..6", setOf("ORDERS")),
                    simple("0052", M, "an..3", setOf("D")),
                    simple("0054", M, "an..3", setOf("96A")),
                    simple("0051", M, "an..2", setOf("UN")),
                ),
            ),
        )

    private val unt = SegmentDefinition("UNT", listOf(simple("0074", M, "n..6"), simple("0062", M, "an..14")))
    private val unz = SegmentDefinition("UNZ", listOf(simple("0036", M, "n..6"), simple("0020", M, "an..14")))

    val orders: EdiSpec =
        EdiSpec(
            name = "test-orders",
            version = 1,
            direction = Direction.INBOUND,
            message = MessageIdentifier("ORDERS", "D", "96A", "UN"),
            syntax = SyntaxRules(setOf(3), setOf(CharacterSet.UNOA, CharacterSet.UNOB, CharacterSet.UNOC), maxMessagesPerInterchange = 3),
            segments =
                mapOf(
                    "UNB" to unb,
                    "UNH" to unh,
                    "UNT" to unt,
                    "UNZ" to unz,
                    "BGM" to
                        SegmentDefinition(
                            "BGM",
                            listOf(
                                composite("C002", M, simple("1001", M, "an..3", setOf("220"))),
                                simple("1004", M, "an..35"),
                                simple("1225", C, "an..3", setOf("9")),
                            ),
                        ),
                    "DTM" to
                        SegmentDefinition(
                            "DTM",
                            listOf(
                                composite(
                                    "C507",
                                    M,
                                    simple("2005", M, "an..3", setOf("137", "2")),
                                    simple("2380", M, "n8", semantic = Semantic.DATE),
                                    simple("2379", M, "an..3", setOf("102")),
                                ),
                            ),
                        ),
                    "NAD" to
                        SegmentDefinition(
                            "NAD",
                            listOf(simple("3035", M, "an..3", setOf("BY", "SU")), composite("C082", M, simple("3039", M, "an..35"))),
                        ),
                    "LOC" to SegmentDefinition("LOC", listOf(simple("3227", M, "a..3"))),
                    "QTY" to
                        SegmentDefinition(
                            "QTY",
                            listOf(composite("C186", M, simple("6063", M, "an..3", setOf("21")), simple("6060", M, "n..15"))),
                        ),
                ),
            structure =
                listOf(
                    StructureNode.SegmentNode("BGM", mandatory = true, max = 1),
                    StructureNode.SegmentNode(
                        "DTM",
                        mandatory = true,
                        max = 2,
                        qualifiers = listOf(Qualifier("137", true, 1), Qualifier("2", false, 1)),
                    ),
                    StructureNode.GroupNode(
                        "SG1",
                        mandatory = true,
                        max = 2,
                        content =
                            listOf(
                                StructureNode.SegmentNode("NAD", mandatory = true, max = 1),
                                StructureNode.SegmentNode("LOC", mandatory = false, max = 1),
                                StructureNode.SegmentNode("QTY", mandatory = false, max = 1),
                            ),
                        qualifiers = listOf(Qualifier("BY", true, 1), Qualifier("SU", false, 1)),
                    ),
                ),
        )

    /** CONTRL の仕様(応答の組み立てで使う項目だけ)。 */
    val contrl: EdiSpec =
        orders.copy(
            name = "test-contrl",
            direction = Direction.OUTBOUND,
            message = MessageIdentifier("CONTRL", "D", "3", "UN"),
            structure = emptyList(),
        )
}

/** 正しい本文(BGM・DTM・NAD+BY・NAD+SU)。 */
internal val VALID_BODY: List<String> =
    listOf("BGM+220+PO-1+9", "DTM+137:20261030:102", "NAD+BY+4912345000019", "LOC+ABC", "NAD+SU+4900000000001")

/**
 * 交換の文字列を作る。[messages] はメッセージごとの本文(UNH と UNT は自動で付け、UNT の件数も数える)。
 * [unb] で UNB を、[unz] で UNZ を差し替えられる(誤りのサンプル用)。
 */
internal fun interchange(
    vararg messages: List<String>,
    una: String? = "UNA:+.? '",
    unb: String = "UNB+UNOA:3+4912345000019:14+4900000000001:14+261030:1200+ICR-1",
    unz: String? = null,
): String {
    val segments = mutableListOf(unb)
    messages.forEachIndexed { index, body ->
        val reference = "M${index + 1}"
        segments += "UNH+$reference+ORDERS:D:96A:UN"
        segments += body
        segments += "UNT+${body.size + 2}+$reference"
    }
    segments += unz ?: "UNZ+${messages.size}+ICR-1"
    return (una ?: "") + segments.joinToString("'", postfix = "'")
}
