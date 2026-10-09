package io.eia.payment.application.port.inbound

/** コマンドの処理の結果。どちらも成功(オフセットを進める)。 */
public enum class CommandOutcome {
    /** 処理した(返事を書いた)。 */
    PROCESSED,

    /** 同じメッセージ(`ce_id`)を処理済みだった。何もしない。 */
    DUPLICATE,
}

/**
 * 受け取ったコマンドの共通の項目。
 *
 * @property messageId `ce_id`(冪等消費のキー)
 * @property topic 受け取ったトピック(冪等消費の記録の調査用)
 */
public data class CommandEnvelope(
    public val messageId: String,
    public val topic: String,
    public val sagaId: String,
    public val orderId: String,
)
