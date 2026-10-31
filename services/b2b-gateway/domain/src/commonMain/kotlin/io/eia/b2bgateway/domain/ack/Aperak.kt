package io.eia.b2bgateway.domain.ack

import io.eia.b2bgateway.domain.edifact.Segment
import io.eia.b2bgateway.domain.spec.EdiSpec
import io.eia.b2bgateway.domain.spec.RuleText
import io.eia.b2bgateway.domain.validation.InterchangeHeader

/**
 * 1 つの注文(受け取った ORDERS の 1 メッセージ)への業務の応答。[errors] が空なら受け付け(4343 = AP)、あれば拒否(RE)。
 * 文言は仕様の規則(businessRules・errorTexts)から取る(ADR-0041 §5。社内の情報を入れない)。
 */
public data class BusinessResponse(
    public val orderNumber: String,
    public val errors: List<RuleText>,
) {
    public val accepted: Boolean get() = errors.isEmpty()
}

/**
 * 業務の段階の応答(APERAK。contracts/files/edi/edifact-aperak.v1.yaml。ADR-0041 §5)を組み立てる。受け取った交換 1 つに対して、
 * 応答する注文ごとに 1 つのメッセージを入れた交換を 1 つ作る。受け取った交換の区切り文字・文字セットで書く([Envelopes.write])。
 */
public object Aperak {
    private const val STATUS_INFORMATION = "23"
    private const val ORIGINAL = "9"
    private const val ACCEPTED = "AP"
    private const val REJECTED = "RE"
    private const val DOCUMENT_DATE = "137"
    private const val CCYYMMDD = "102"
    private const val BUYERS_ORDER_NUMBER = "CO"
    private const val MUTUALLY_DEFINED = "ZZZ"
    private const val ERROR_DESCRIPTION = "AAO"

    public fun build(
        spec: EdiSpec,
        header: InterchangeHeader,
        responses: List<BusinessResponse>,
        envelope: ResponseEnvelope,
    ): List<Segment> {
        require(responses.isNotEmpty()) { "応答する注文がありません" }
        val date = UtcStamp.calendarDate(envelope.preparedAt)
        val messages =
            responses.flatMapIndexed { index, response ->
                val reference = (index + 1).toString()
                val body =
                    listOf(
                        Segment.of(
                            "BGM",
                            listOf(STATUS_INFORMATION),
                            listOf("${envelope.controlReference}-$reference"),
                            listOf(ORIGINAL),
                            listOf(if (response.accepted) ACCEPTED else REJECTED),
                        ),
                        Segment.of("DTM", listOf(DOCUMENT_DATE, date, CCYYMMDD)),
                        Segment.of("RFF", listOf(BUYERS_ORDER_NUMBER, response.orderNumber)),
                    ) +
                        response.errors.flatMap {
                            listOf(
                                Segment.of("ERC", listOf(it.code, "", MUTUALLY_DEFINED)),
                                Segment.of("FTX", listOf(ERROR_DESCRIPTION), listOf(""), listOf(""), listOf(it.text)),
                            )
                        }
                listOf(Segment.of("UNH", listOf(reference), spec.message.values)) +
                    body +
                    Segment.of("UNT", listOf((body.size + 2).toString()), listOf(reference))
            }
        return listOf(Envelopes.unb(spec, header, envelope)) + messages +
            Segment.of("UNZ", listOf(responses.size.toString()), listOf(envelope.controlReference))
    }
}
