package io.eia.b2bgateway.domain.ack

import io.eia.b2bgateway.domain.edifact.EdifactWriter
import io.eia.b2bgateway.domain.edifact.Latin1
import io.eia.b2bgateway.domain.edifact.Segment
import io.eia.b2bgateway.domain.spec.EdiSpec
import io.eia.b2bgateway.domain.validation.InterchangeHeader
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.b2bgateway.domain.validation.MessageResult
import io.eia.b2bgateway.domain.validation.ServiceError
import kotlin.time.Instant

/**
 * 応答の交換の枠(自社が振る番号と、作った時刻)。[controlReference] は UNB / UNZ の 0020(取引先ごとに振る。an..14)、
 * [messageReference] は UNH / UNT の 0062。
 */
public data class ResponseEnvelope(
    public val controlReference: String,
    public val messageReference: String,
    public val preparedAt: Instant,
)

/**
 * 構文とスキーマの段階の応答(CONTRL。contracts/files/edi/edifact-contrl.v1.yaml。ADR-0041 §4・§5)を組み立てる。
 * - 交換の誤り([InterchangeResult.Rejected]): UCI の 0083 = 4 と誤りだけ(UCM は付けない)。
 * - 交換の枠が正しい([InterchangeResult.Checked]): UCI の 0083 = 7 と、メッセージごとの UCM(受け付け 7 / 拒否 4。拒否は UCS・UCD で位置を示す)。
 *
 * 誤りは標準のコード(0085)と位置だけで表し、文言や社内の情報は入れない。受け取った交換の区切り文字・文字セットで書き、
 * 識別子と管理番号は受け取った値をそのまま写す。
 */
public object Contrl {
    private const val REJECTED = "4"
    private const val ACKNOWLEDGED = "7"
    private const val TEST_INDICATOR_POSITION = 11
    private const val S009_COMPONENTS = 4

    /** CONTRL のセグメント。UNB を読めない交換([InterchangeResult.Unreadable])は宛先がわからないので、応答を作らずに null。 */
    public fun build(
        spec: EdiSpec,
        result: InterchangeResult,
        envelope: ResponseEnvelope,
    ): List<Segment>? {
        val (header, body) =
            when (result) {
                is InterchangeResult.Rejected -> {
                    result.header to listOf(uci(result.header, REJECTED, result.error))
                }

                is InterchangeResult.Checked -> {
                    result.header to
                        listOf(uci(result.header, ACKNOWLEDGED, null)) + result.messages.flatMap(::ucm)
                }

                is InterchangeResult.Unreadable -> {
                    return null
                }
            }
        val unh = Segment.of("UNH", listOf(envelope.messageReference), spec.message.values)
        val unt = Segment.of("UNT", listOf((body.size + 2).toString()), listOf(envelope.messageReference))
        return listOf(unb(spec, header, envelope)) + unh + body + unt + Segment.of("UNZ", listOf("1"), listOf(envelope.controlReference))
    }

    /** 組み立てた CONTRL を、受け取った交換の区切り文字で書き、受け取った文字セットのバイト列にする。 */
    public fun write(
        header: InterchangeHeader,
        segments: List<Segment>,
    ): ByteArray = Latin1.encode(EdifactWriter.write(header.serviceCharacters, segments))

    private fun unb(
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

    private fun uci(
        header: InterchangeHeader,
        action: String,
        error: ServiceError?,
    ): Segment =
        Segment(
            "UCI",
            listOf(
                listOf(header.controlReference),
                listOf(header.sender.id, header.sender.qualifier),
                listOf(header.recipient.id, header.recipient.qualifier),
                listOf(action),
            ) + errorElements(error),
        )

    private fun ucm(message: MessageResult): List<Segment> {
        val action = if (message.accepted) ACKNOWLEDGED else REJECTED
        val ucm =
            Segment(
                "UCM",
                listOf(listOf(message.reference), message.identifier.take(S009_COMPONENTS), listOf(action)) + errorElements(message.header),
            )
        val segments =
            message.errors.flatMap { error ->
                val ucs = Segment.of("UCS", listOf(error.position.toString()), listOf(error.code?.code.orEmpty()))
                val ucds =
                    error.elements.map {
                        Segment.of("UCD", listOf(it.code.code), listOfNotNull(it.element.toString(), it.component?.toString()))
                    }
                listOf(ucs) + ucds
            }
        return listOf(ucm) + segments
    }

    /** UCI・UCM の 0085・0013・S011。誤りがなければ空。 */
    private fun errorElements(error: ServiceError?): List<List<String>> =
        if (error == null) {
            emptyList()
        } else {
            listOf(
                listOf(error.code.code),
                listOf(error.serviceSegment.orEmpty()),
                listOfNotNull(error.element?.toString(), error.component?.toString()),
            )
        }
}
