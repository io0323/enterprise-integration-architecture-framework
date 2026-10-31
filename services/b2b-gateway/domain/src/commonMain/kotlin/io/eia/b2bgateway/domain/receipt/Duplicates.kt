package io.eia.b2bgateway.domain.receipt

import io.eia.b2bgateway.domain.validation.PartyIdentification

/**
 * 交換の重複の判定のキー(EDI の層での冪等。ADR-0041 §11)。取引先・UNB の送り手・受け手・交換の管理番号(0020)の組。
 */
public data class InterchangeKey(
    public val partnerId: String,
    public val sender: PartyIdentification,
    public val recipient: PartyIdentification,
    public val controlReference: String,
)

/** 前に受け取った交換。[contrl]・[aperak] は、そのときに返した応答(返していなければ null)。 */
public class PreviousReceipt(
    public val sha256: String,
    public val contrl: ByteArray?,
    public val aperak: ByteArray?,
)

/** 重複の判定の結果。 */
public sealed interface DuplicateCheck {
    /** 初めて届いた管理番号 */
    public data object First : DuplicateCheck

    /** 同じ管理番号・同じ中身(SHA-256)の送り直し。取り込まず、前に返した応答を返し直す */
    public data class Resend(
        public val previous: PreviousReceipt,
    ) : DuplicateCheck

    /** 同じ管理番号で中身が違う(管理番号の使い回し)。交換を拒否する(0085 = 26) */
    public data object Reused : DuplicateCheck
}

public object Duplicates {
    public fun decide(
        previous: PreviousReceipt?,
        sha256: String,
    ): DuplicateCheck =
        when {
            previous == null -> DuplicateCheck.First
            previous.sha256 == sha256 -> DuplicateCheck.Resend(previous)
            else -> DuplicateCheck.Reused
        }
}
