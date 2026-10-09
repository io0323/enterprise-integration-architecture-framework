package io.eia.payment.application.port.outbound

import io.eia.payment.domain.Authorization
import io.eia.payment.domain.AuthorizeReply
import io.eia.payment.domain.VoidOutcome
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Duration

/**
 * 業務の更新の範囲(トランザクション)。[block] が `Ok` を返せば確定し、`Err` を返すか例外を投げれば取り消す。
 * 冪等消費の記録・承認の記録・返事(Outbox)は、すべてこの中で書く(ADR-0028 §3・ADR-0007)。
 */
public interface TransactionRunner {
    public suspend fun <T> inTransaction(block: suspend () -> Result<T, DomainError>): Result<T, DomainError>
}

/** 冪等消費の記録(processed_message。ADR-0028 §3)。adapters が `platform/inbox` で実装する。 */
public interface ProcessedCommands {
    /** [messageId](`ce_id`)を記録する。初めてなら true、処理済みなら false。トランザクションの中で呼ぶ。 */
    public suspend fun markProcessed(
        messageId: String,
        topic: String,
    ): Result<Boolean, DomainError>

    /** 保持期間(ADR-0028 §3)を過ぎた記録を、上限の件数まで消す。消した件数を返す。 */
    public suspend fun purgeExpired(batchSize: Int): Result<Int, DomainError>
}

/** 承認の記録(Saga ID ごとに 1 行。ADR-0029 §5)。 */
public interface AuthorizationStore {
    /** [sagaId] の記録をロックして読む(同じ Saga の処理を直列にする)。なければ null。 */
    public suspend fun findForUpdate(sagaId: String): Result<Authorization?, DomainError>

    /** 新しい記録を書く。終わった状態なら、終わった時刻を DB の時計で記録する。 */
    public suspend fun insert(authorization: Authorization): Result<Unit, DomainError>

    /** [sagaId] の承認を取り消し済みにし、終わった時刻を DB の時計で記録する。 */
    public suspend fun markVoided(sagaId: String): Result<Unit, DomainError>

    /** DB の時計で [retention] より前に終わった記録(印を含む)を、上限の件数まで消す。有効な承認は消さない。 */
    public suspend fun purgeSettled(
        retention: Duration,
        batchSize: Int,
    ): Result<Int, DomainError>
}

/** 承認 ID の採番。 */
public fun interface AuthorizationIds {
    public fun next(): String
}

/** 返事のイベント(INT-PAYMENT-002)。adapters が Outbox で書く。トランザクションの中で呼ぶ。 */
public interface PaymentReplies {
    public suspend fun authorizeReplied(
        sagaId: String,
        orderId: String,
        reply: AuthorizeReply,
    ): Result<Unit, DomainError>

    public suspend fun voided(
        sagaId: String,
        orderId: String,
        outcome: VoidOutcome,
    ): Result<Unit, DomainError>
}
