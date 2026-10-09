package io.eia.order.application.port.outbound

import io.eia.order.domain.Order
import io.eia.order.domain.Saga
import io.eia.order.domain.SagaCommand
import io.eia.order.domain.SagaSignal
import io.eia.order.domain.SagaState
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Duration

/**
 * 注文 Saga の記録(`order_saga`。ADR-0029 §1・§6)。
 *
 * **段の期限は DB の時計で決める**: [insert] と [save] は `deadline_at = clock_timestamp() + [stepTimeout]` を書き、
 * [lockExpired] は `deadline_at <= clock_timestamp()` で選ぶ。アプリの時計は使わない(ご指示。P05 の冪等のリースと同じ理由)。
 */
public interface SagaStore {
    /** 新しい Saga を書く。[stepTimeout] が null(終端)なら期限を持たない。トランザクションの中で呼ぶ。 */
    public suspend fun insert(
        saga: Saga,
        stepTimeout: Duration?,
    ): Result<Unit, DomainError>

    /** [sagaId] の Saga をロックして読む(返信の処理と期限切れの処理を直列にする)。なければ null。 */
    public suspend fun findForUpdate(sagaId: String): Result<Saga?, DomainError>

    /** 状態・理由・送り直しの回数と、次の段の期限(DB の時計 + [stepTimeout]。null なら期限なし)を書く。 */
    public suspend fun save(
        saga: Saga,
        stepTimeout: Duration?,
    ): Result<Unit, DomainError>

    /**
     * DB の時計で期限を過ぎた終端でない Saga を、最大 [limit] 件ロックして返す(`FOR UPDATE SKIP LOCKED`。
     * ほかのインスタンスが処理中の行は飛ばす)。トランザクションの中で呼ぶ。
     */
    public suspend fun lockExpired(limit: Int): Result<List<Saga>, DomainError>
}

/** Saga のコマンドの発行(Outbox。ADR-0007・ADR-0029 §2)。トランザクションの中で呼ぶ。コマンドの中身は注文から作る。 */
public fun interface SagaCommandOutbox {
    public suspend fun send(
        command: SagaCommand,
        saga: Saga,
        order: Order,
    ): Result<Unit, DomainError>
}

/** Saga ID の採番(UUIDv7)。 */
public fun interface SagaIdGenerator {
    public fun next(): String
}

/** 返信の冪等消費の記録(processed_message。ADR-0028 §3)。トランザクションの中で呼ぶ。 */
public fun interface ProcessedReplies {
    /** [messageId](`ce_id`)を記録する。初めてなら true、処理済みなら false。 */
    public suspend fun markProcessed(
        messageId: String,
        topic: String,
    ): Result<Boolean, DomainError>
}

/** Saga の進み方の通知(メトリクス。ADR-0029 §3)。 */
public interface SagaObserver {
    public fun transitioned(
        saga: Saga,
        from: SagaState,
    ) {}

    /** 補償のコマンドを送り直した([Saga.resends] は送り直した後の回数)。 */
    public fun resent(saga: Saga) {}

    /** 今の段に関係しない結果を無視した。 */
    public fun ignored(
        state: SagaState,
        signal: SagaSignal,
    ) {}

    public companion object {
        public val NONE: SagaObserver = object : SagaObserver {}
    }
}
