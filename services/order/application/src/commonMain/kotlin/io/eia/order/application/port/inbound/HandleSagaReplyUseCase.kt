package io.eia.order.application.port.inbound

import io.eia.order.domain.SagaSignal
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 参加者の返信(ADR-0029 §2)。
 *
 * @property messageId `ce_id`(冪等消費のキー)
 * @property topic 受け取ったトピック(冪等消費の記録の調査用)
 */
public data class SagaReply(
    public val messageId: String,
    public val topic: String,
    public val sagaId: String,
    public val signal: SagaSignal,
)

/** 返信の処理の結果。どちらも成功(オフセットを進める)。 */
public enum class ReplyOutcome {
    /** 判定して反映した(無視した場合も含む)。 */
    PROCESSED,

    /** 同じメッセージ(`ce_id`)を処理済みだった。 */
    DUPLICATE,
}

/** 参加者の返信で Saga を進める(order-service の Consumer Group `order.saga`)。 */
public interface HandleSagaReplyUseCase {
    public suspend operator fun invoke(reply: SagaReply): Result<ReplyOutcome, DomainError>
}
