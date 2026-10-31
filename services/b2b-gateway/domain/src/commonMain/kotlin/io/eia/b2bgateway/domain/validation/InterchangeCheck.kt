package io.eia.b2bgateway.domain.validation

import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.LexIssue
import io.eia.b2bgateway.domain.edifact.LexIssueKind
import io.eia.b2bgateway.domain.edifact.Lexed
import io.eia.b2bgateway.domain.edifact.Segment
import io.eia.b2bgateway.domain.spec.EdiSpec
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * 構文とスキーマの段階の検査(ADR-0041 §4)。
 * - 交換の枠(UNA・UNB・UNZ・メッセージの外のセグメント・件数と管理番号): 誤りは交換を拒否する([InterchangeResult.Rejected])。
 * - メッセージの枠(UNH・UNT): 誤りはそのメッセージを拒否する([MessageResult.header])。
 * - メッセージの中身(データ要素と並び): 誤りはそのメッセージを拒否し、位置を示す([MessageResult.errors])。
 *
 * 取引先の台帳との照合(UNB の送り手・受け手)と、交換の管理番号の重複は、ここでは見ない(application が台帳と受け取りの記録で行う)。
 */
public object InterchangeCheck {
    /** CONTRL の SG1(UCM)・SG2(UCS)・UCD の上限(edifact-contrl.v1 の max)。超えた分は報告しない(拒否は変わらない)。 */
    public const val MAX_SEGMENT_ERRORS: Int = 99
    public const val MAX_ELEMENT_ERRORS: Int = 99

    private const val UNB = "UNB"
    private const val UNZ = "UNZ"
    private const val UNH = "UNH"
    private const val UNT = "UNT"
    private const val UNA = "UNA"
    private const val UNG = "UNG"

    // UNB・UNZ のデータ要素の位置(ISO 9735 の構文の版 3)
    private const val UNB_SYNTAX = 1
    private const val UNB_SENDER = 2
    private const val UNB_RECIPIENT = 3
    private const val UNB_CONTROL_REFERENCE = 5
    private const val TEST_INDICATOR = 11
    private const val UNZ_COUNT = 1
    private const val UNZ_CONTROL_REFERENCE = 2
    private const val UNA_PREFIX = 3
    private const val FIRST_BODY_POSITION = 2
    private val SERVICE_TAGS = setOf(UNB, UNZ, UNH, UNT)

    /** 文字セットが読めないときに応答に使う文字セット(1 バイトの文字はどれも表せる。ADR-0041 §3)。 */
    private val RESPONSE_FALLBACK = CharacterSet.UNOC

    @Suppress("ReturnCount") // 宛先がわからない・交換の枠の誤り・メッセージの検査の 3 つの出口
    public fun check(
        lexed: Lexed,
        spec: EdiSpec,
    ): InterchangeResult {
        val unb = lexed.segments.firstOrNull()?.takeIf { it.tag == UNB }
        val header = unb?.let { header(lexed, it) } ?: return InterchangeResult.Unreadable(ServiceError(SyntaxErrorCode.MISSING, UNB))
        val context = Context(lexed, spec, header.characterSet)
        envelopeError(context, unb)?.let { return InterchangeResult.Rejected(header, it) }
        return when (val split = splitMessages(context)) {
            is Result.Err -> InterchangeResult.Rejected(header, split.error)
            is Result.Ok -> InterchangeResult.Checked(header, split.value.map { checkMessage(context, it) })
        }
    }

    private class Context(
        val lexed: Lexed,
        val spec: EdiSpec,
        val characterSet: CharacterSet,
    ) {
        val decimal: Char get() = lexed.serviceCharacters.decimal
        val issuesBySegment: Map<Int?, List<LexIssue>> = lexed.issues.groupBy { it.segmentIndex }
    }

    private fun header(
        lexed: Lexed,
        unb: Segment,
    ): InterchangeHeader? {
        val sender = PartyIdentification(unb.value(UNB_SENDER, 1), unb.value(UNB_SENDER, 2))
        val recipient = PartyIdentification(unb.value(UNB_RECIPIENT, 1), unb.value(UNB_RECIPIENT, 2))
        val reference = unb.value(UNB_CONTROL_REFERENCE)
        if (sender.id.isEmpty() || recipient.id.isEmpty() || reference.isEmpty()) return null
        return InterchangeHeader(
            serviceCharacters = lexed.serviceCharacters,
            characterSet = CharacterSet.of(unb.value(UNB_SYNTAX, 1)) ?: RESPONSE_FALLBACK,
            syntaxVersion = unb.value(UNB_SYNTAX, 2),
            sender = sender,
            recipient = recipient,
            controlReference = reference,
            test = unb.value(TEST_INDICATOR) == "1",
        )
    }

    /** 交換の枠の誤り(最初の 1 つ。UCI は 0085 を 1 つしか持てない)。 */
    @Suppress("ReturnCount") // 枠の検査は順に見て、最初の誤りで返す
    private fun envelopeError(
        context: Context,
        unb: Segment,
    ): ServiceError? {
        val lexed = context.lexed
        context.issuesBySegment[null]?.firstOrNull()?.let {
            return ServiceError(
                if (it.kind ==
                    LexIssueKind.UNA_MISSING
                ) {
                    SyntaxErrorCode.MISSING
                } else {
                    SyntaxErrorCode.INVALID_SERVICE_CHARACTER
                },
                UNA,
            )
        }
        lexed.una?.drop(UNA_PREFIX)?.firstOrNull { it != ' ' && !context.characterSet.allows(it) }?.let {
            return ServiceError(SyntaxErrorCode.INVALID_SERVICE_CHARACTER, UNA)
        }
        if (unb.value(1, 2).toIntOrNull() !in context.spec.syntax.versions) {
            return ServiceError(SyntaxErrorCode.UNSUPPORTED_SYNTAX_VERSION, UNB, 1, 2)
        }
        if (CharacterSet.of(unb.value(1, 1)) !in
            context.spec.syntax.characterSets
        ) {
            return ServiceError(SyntaxErrorCode.INVALID_VALUE, UNB, 1, 1)
        }
        serviceSegmentError(context, unb)?.let { return it }
        lexed.segments.firstOrNull { it.tag == UNG }?.let { return ServiceError(SyntaxErrorCode.NOT_SUPPORTED_IN_POSITION, UNG) }
        val unz = lexed.segments.last()
        if (!lexed.terminated || unz.tag != UNZ || lexed.segments.size < 2) return ServiceError(SyntaxErrorCode.MISSING, UNZ)
        return serviceSegmentError(context, unz)
    }

    /** サービスセグメント([segment] の tag)の字句の誤りと、データ要素の誤り。 */
    private fun serviceSegmentError(
        context: Context,
        segment: Segment,
    ): ServiceError? {
        context.issuesBySegment[segment.index]?.firstOrNull()?.let {
            return ServiceError(
                lexCode(it.kind),
                segment.tag,
                it.element,
                it.component,
            )
        }
        return ElementCheck
            .check(context.spec.service(segment.tag), segment, context.characterSet, context.decimal)
            .firstOrNull()
            ?.let { ServiceError(it.code, segment.tag, it.element, it.component) }
    }

    /** UNB と UNZ の間をメッセージ(UNH〜UNT)に分け、件数と管理番号を UNZ と照らし合わせる。 */
    @Suppress("ReturnCount") // 交換の枠の誤りは、最初の 1 つで返す
    private fun splitMessages(context: Context): Result<List<List<Segment>>, ServiceError> {
        val segments = context.lexed.segments
        val inner = segments.subList(1, segments.size - 1)
        val messages = mutableListOf<List<Segment>>()
        var index = 0
        while (index < inner.size) {
            if (inner[index].tag != UNH) return err(ServiceError(SyntaxErrorCode.INVALID_OCCURRENCE_OUTSIDE_MESSAGE))
            // UNT まで(UNT がなければ、次の UNH か UNZ の前まで)を 1 つのメッセージにする
            val next = inner.subList(index + 1, inner.size).indexOfFirst { it.tag == UNT || it.tag == UNH }
            val end = if (next < 0) inner.size else index + 1 + next
            val last = if (end < inner.size && inner[end].tag == UNT) end + 1 else end
            messages += inner.subList(index, last)
            index = last
        }
        val unb = segments.first()
        val unz = segments.last()
        return when {
            messages.isEmpty() -> {
                err(ServiceError(SyntaxErrorCode.LOWER_LEVEL_EMPTY))
            }

            messages.size > context.spec.syntax.maxMessagesPerInterchange -> {
                err(
                    ServiceError(SyntaxErrorCode.TOO_MANY_SEGMENT_REPETITIONS, UNH),
                )
            }

            unz.value(UNZ_COUNT) != messages.size.toString() -> {
                err(ServiceError(SyntaxErrorCode.CONTROL_COUNT_MISMATCH, UNZ, UNZ_COUNT))
            }

            unz.value(UNZ_CONTROL_REFERENCE) != unb.value(UNB_CONTROL_REFERENCE) -> {
                err(ServiceError(SyntaxErrorCode.REFERENCES_DO_NOT_MATCH, UNZ, UNZ_CONTROL_REFERENCE))
            }

            else -> {
                ok(messages)
            }
        }
    }

    private fun checkMessage(
        context: Context,
        segments: List<Segment>,
    ): MessageResult {
        val unh = segments.first()
        val unt = segments.last().takeIf { it.tag == UNT && segments.size > 1 }
        val body = segments.subList(1, if (unt != null) segments.size - 1 else segments.size)
        return MessageResult(
            reference = unh.value(1),
            identifier = unh.elements.getOrNull(1).orEmpty(),
            segments = segments,
            header = messageHeaderError(context, unh, unt, segments.size),
            errors = bodyErrors(context, body),
        )
    }

    @Suppress("ReturnCount") // メッセージの枠の誤りは、最初の 1 つで返す(UCM は 0085 を 1 つしか持てない)
    private fun messageHeaderError(
        context: Context,
        unh: Segment,
        unt: Segment?,
        count: Int,
    ): ServiceError? {
        serviceSegmentError(context, unh)?.let { return it }
        if (unt == null) return ServiceError(SyntaxErrorCode.MISSING, UNT)
        serviceSegmentError(context, unt)?.let { return it }
        if (unt.value(2) != unh.value(1)) return ServiceError(SyntaxErrorCode.REFERENCES_DO_NOT_MATCH, UNT, 2)
        if (unt.value(1) != count.toString()) return ServiceError(SyntaxErrorCode.CONTROL_COUNT_MISMATCH, UNT, 1)
        return null
    }

    /** 本文のセグメントの誤り(データ要素・字句・並び)を、位置ごとにまとめる。 */
    private fun bodyErrors(
        context: Context,
        body: List<Segment>,
    ): List<SegmentError> {
        val segmentErrors =
            body.flatMapIndexed { index, segment ->
                val position = index + FIRST_BODY_POSITION
                val lexical = context.issuesBySegment[segment.index].orEmpty().map { lexError(position, it) }
                val definition = context.spec.segments[segment.tag]?.takeIf { segment.tag !in SERVICE_TAGS }
                val elements = definition?.let { ElementCheck.check(it, segment, context.characterSet, context.decimal) }.orEmpty()
                lexical + SegmentError(position, null, elements)
            }
        val structure = StructureCheck.check(context.spec.structure, body)
        return (segmentErrors + structure)
            .groupBy { it.position }
            .map { (position, errors) ->
                SegmentError(position, errors.firstNotNullOfOrNull { it.code }, errors.flatMap { it.elements }.take(MAX_ELEMENT_ERRORS))
            }.filter { it.code != null || it.elements.isNotEmpty() }
            .sortedBy { it.position }
            .take(MAX_SEGMENT_ERRORS)
    }

    /** 本文のセグメントの字句の誤り。タグの誤りはセグメントの誤り、解放文字の誤りはデータ要素の誤り。 */
    private fun lexError(
        position: Int,
        issue: LexIssue,
    ): SegmentError =
        if (issue.kind == LexIssueKind.INVALID_TAG) {
            SegmentError(position, SyntaxErrorCode.INVALID_VALUE)
        } else {
            SegmentError(position, null, listOfNotNull(issue.element?.let { ElementError(lexCode(issue.kind), it, issue.component) }))
        }

    private fun lexCode(kind: LexIssueKind): SyntaxErrorCode =
        when (kind) {
            LexIssueKind.UNA_MISSING -> SyntaxErrorCode.MISSING
            LexIssueKind.INVALID_TAG -> SyntaxErrorCode.INVALID_VALUE
            LexIssueKind.UNA_INVALID, LexIssueKind.RELEASE_NOT_BEFORE_SERVICE_CHARACTER -> SyntaxErrorCode.INVALID_SERVICE_CHARACTER
        }
}
