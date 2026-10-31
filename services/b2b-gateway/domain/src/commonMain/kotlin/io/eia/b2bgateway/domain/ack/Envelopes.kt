package io.eia.b2bgateway.domain.ack

import io.eia.b2bgateway.domain.edifact.EdifactWriter
import io.eia.b2bgateway.domain.edifact.Latin1
import io.eia.b2bgateway.domain.edifact.Segment
import io.eia.b2bgateway.domain.spec.EdiSpec
import io.eia.b2bgateway.domain.validation.InterchangeHeader

/** 応答(CONTRL・APERAK)の交換の枠。受け取った交換の宛先を入れ替え、区切り文字・文字セット・試験の印を引き継ぐ(ADR-0041 §3・§5・§6)。 */
public object Envelopes {
    private const val TEST_INDICATOR_POSITION = 11

    /** 応答の UNB。送り手は自社(受け取った交換の受け手)、受け手は取引先(受け取った交換の送り手)。日時は UTC。 */
    public fun unb(
        spec: EdiSpec,
        header: InterchangeHeader,
        envelope: ResponseEnvelope,
    ): Segment {
        val prepared = UtcStamp.of(envelope.preparedAt)
        val elements =
            mutableListOf(
                listOf(
                    header.characterSet.identifier,
                    spec.syntax.versions
                        .min()
                        .toString(),
                ),
                listOf(header.recipient.id, header.recipient.qualifier),
                listOf(header.sender.id, header.sender.qualifier),
                listOf(prepared.date, prepared.time),
                listOf(envelope.controlReference),
            )
        if (header.test) {
            while (elements.size < TEST_INDICATOR_POSITION - 1) elements += listOf("")
            elements += listOf("1")
        }
        return Segment("UNB", elements)
    }

    /** 応答を、受け取った交換の区切り文字で書き、受け取った文字セットのバイト列にする。 */
    public fun write(
        header: InterchangeHeader,
        segments: List<Segment>,
    ): ByteArray = Latin1.encode(EdifactWriter.write(header.serviceCharacters, segments))
}
