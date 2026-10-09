package io.eia.inventory.application.port.outbound

import io.eia.inventory.domain.ReleaseOutcome
import io.eia.inventory.domain.Reservation
import io.eia.inventory.domain.ReserveReply
import io.eia.inventory.domain.StockLevel
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Duration

/**
 * 業務の更新の範囲(トランザクション)。[block] が `Ok` を返せば確定し、`Err` を返すか例外を投げれば取り消す。
 * 冪等消費の記録・引当の記録・在庫・返事(Outbox)は、すべてこの中で書く(ADR-0028 §3・ADR-0007)。
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

/** 引当の記録(Saga ID ごとに 1 行。ADR-0029 §5)。 */
public interface ReservationStore {
    /** [sagaId] の記録を、明細とともにロックして読む(同じ Saga の処理を直列にする)。なければ null。 */
    public suspend fun findForUpdate(sagaId: String): Result<Reservation?, DomainError>

    /** 新しい記録を書く。終わった状態([io.eia.inventory.domain.ReservationStatus.isSettled])なら、終わった時刻を DB の時計で記録する。 */
    public suspend fun insert(reservation: Reservation): Result<Unit, DomainError>

    /** [sagaId] の記録を解放済みにし、終わった時刻を DB の時計で記録する。 */
    public suspend fun markReleased(sagaId: String): Result<Unit, DomainError>

    /** DB の時計で [retention] より前に終わった記録(印を含む)を、上限の件数まで消す。有効な引当は消さない。 */
    public suspend fun purgeSettled(
        retention: Duration,
        batchSize: Int,
    ): Result<Int, DomainError>
}

/** SKU ごとの在庫。 */
public interface StockLedger {
    /**
     * [skus] の在庫を、SKU の順にロックして読む(デッドロックを避けるため、どのトランザクションも同じ順にロックする)。
     * 台帳にない SKU は結果に含めない。
     */
    public suspend fun lock(skus: Set<String>): Result<Map<String, StockLevel>, DomainError>

    /** SKU ごとに引当の数を [deltas] だけ変える(正は引当、負は解放)。在庫は負にならない(DB の制約でも守る)。 */
    public suspend fun adjustReserved(deltas: Map<String, Long>): Result<Unit, DomainError>
}

/** 返事のイベント(INT-INVENTORY-002)。adapters が Outbox で書く。トランザクションの中で呼ぶ。 */
public interface InventoryReplies {
    public suspend fun reserveReplied(
        sagaId: String,
        orderId: String,
        reply: ReserveReply,
    ): Result<Unit, DomainError>

    public suspend fun released(
        sagaId: String,
        orderId: String,
        outcome: ReleaseOutcome,
    ): Result<Unit, DomainError>
}
