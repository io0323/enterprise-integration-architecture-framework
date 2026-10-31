package io.eia.b2bgateway.domain.validation

import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.Segment
import io.eia.b2bgateway.domain.edifact.ServiceCharacters

/**
 * 構文の誤りのコード(0085)。使うのは CONTRL の仕様(contracts/files/edi/edifact-contrl.v1.yaml)の codes に並べた値だけ
 * (adapters のテストで一致を確かめる。使う場面は ADR-0041 §4)。
 */
public enum class SyntaxErrorCode(
    public val code: String,
) {
    UNSUPPORTED_SYNTAX_VERSION("2"),
    RECIPIENT_NOT_ACTUAL_RECIPIENT("7"),
    INVALID_VALUE("12"),
    MISSING("13"),
    NOT_SUPPORTED_IN_POSITION("15"),
    TOO_MANY_CONSTITUENTS("16"),
    UNSPECIFIED_ERROR("18"),
    INVALID_DECIMAL_NOTATION("19"),
    INVALID_SERVICE_CHARACTER("20"),
    INVALID_CHARACTERS("21"),
    UNKNOWN_INTERCHANGE_SENDER("23"),
    DUPLICATE_DETECTED("26"),
    REFERENCES_DO_NOT_MATCH("28"),
    CONTROL_COUNT_MISMATCH("29"),
    LOWER_LEVEL_EMPTY("32"),
    INVALID_OCCURRENCE_OUTSIDE_MESSAGE("33"),
    TOO_MANY_SEGMENT_REPETITIONS("35"),
    TOO_MANY_GROUP_REPETITIONS("36"),
    INVALID_CHARACTER_TYPE("37"),
    MISSING_DIGIT_BEFORE_DECIMAL("38"),
    DATA_ELEMENT_TOO_LONG("39"),
    DATA_ELEMENT_TOO_SHORT("40"),
}

/**
 * 交換かメッセージの枠(サービスセグメント)の誤り。CONTRL の UCI か UCM の 0085・0013・S011 になる。
 * [serviceSegment] はサービスセグメントのタグ(なければ null)、[element]・[component] はその中の位置(1 から)。
 */
public data class ServiceError(
    public val code: SyntaxErrorCode,
    public val serviceSegment: String? = null,
    public val element: Int? = null,
    public val component: Int? = null,
)

/** データ要素の誤り(CONTRL の UCD)。位置はタグの次のデータ要素を 1 として数える。 */
public data class ElementError(
    public val code: SyntaxErrorCode,
    public val element: Int,
    public val component: Int? = null,
)

/** セグメントの誤り(CONTRL の UCS と、その下の UCD)。[position] は UNH を 1 として数えたメッセージの中の位置。 */
public data class SegmentError(
    public val position: Int,
    public val code: SyntaxErrorCode? = null,
    public val elements: List<ElementError> = emptyList(),
)

/** UNB / UNZ の送り手・受け手(S002 / S003 の識別子と識別の機関)。 */
public data class PartyIdentification(
    public val id: String,
    public val qualifier: String,
)

/** 受け取った交換の UNB の値(応答の宛先と、重複の判定に使う)。 */
public data class InterchangeHeader(
    public val serviceCharacters: ServiceCharacters,
    public val characterSet: CharacterSet,
    public val syntaxVersion: String,
    public val sender: PartyIdentification,
    public val recipient: PartyIdentification,
    public val controlReference: String,
    public val test: Boolean,
)

/** メッセージ 1 つの検査の結果。[segments] は UNH から UNT まで(UNT がなければ、次の UNH か UNZ の前まで)。 */
public data class MessageResult(
    public val reference: String,
    public val identifier: List<String>,
    public val segments: List<Segment>,
    public val header: ServiceError? = null,
    public val errors: List<SegmentError> = emptyList(),
) {
    public val accepted: Boolean get() = header == null && errors.isEmpty()
}

/** 構文とスキーマの段階の検査の結果(ADR-0041 §4)。 */
public sealed interface InterchangeResult {
    /** UNB を読めず、応答の宛先がわからない。応答を返さず、運用にだけ知らせる。 */
    public data class Unreadable(
        public val reason: ServiceError,
    ) : InterchangeResult

    /** 交換そのものの誤り(UCI で拒否。中のメッセージは検査しない)。 */
    public data class Rejected(
        public val header: InterchangeHeader,
        public val error: ServiceError,
    ) : InterchangeResult

    /** 交換の枠は正しい。メッセージごとの結果を持つ(UCI で受け付け、UCM で個別に受け付け・拒否)。 */
    public data class Checked(
        public val header: InterchangeHeader,
        public val messages: List<MessageResult>,
    ) : InterchangeResult
}
