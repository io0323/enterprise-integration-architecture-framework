package io.eia.shipping.application.port.outbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shipping.domain.ArrangeReply
import io.eia.shipping.domain.CancelOutcome
import io.eia.shipping.domain.Shipment
import io.eia.shipping.domain.ShipmentStamp
import kotlin.time.Duration

/**
 * 業務の更新の範囲(トランザクション)。[block] が `Ok` を返せば確定し、`Err` を返すか例外を投げれば取り消す。
 * 冪等消費の記録・出荷の記録・返事(Outbox)は、すべてこの中で書く(ADR-0028 §3・ADR-0007)。
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

/** 出荷の記録(Saga ID ごとに 1 行。ADR-0029 §5)。 */
public interface ShipmentStore {
    /** [sagaId] の記録をロックして読む(同じ Saga の処理を直列にする)。なければ null。 */
    public suspend fun findForUpdate(sagaId: String): Result<Shipment?, DomainError>

    /** 新しい記録を書く。終わった状態(拒否・印)なら、終わった時刻を DB の時計で記録する。 */
    public suspend fun insert(shipment: Shipment): Result<Unit, DomainError>

    /** DB の時計で [retention] より前に終わった記録(印を含む)を、上限の件数まで消す。出荷した記録は消さない。 */
    public suspend fun purgeSettled(
        retention: Duration,
        batchSize: Int,
    ): Result<Int, DomainError>
}

/** 出荷 ID の採番と出荷の時刻。 */
public fun interface ShipmentStamps {
    public fun next(): ShipmentStamp
}

/** 返事のイベント(INT-SHIPPING-002)。adapters が Outbox で書く。トランザクションの中で呼ぶ。 */
public interface ShippingReplies {
    public suspend fun arrangeReplied(
        sagaId: String,
        orderId: String,
        reply: ArrangeReply,
    ): Result<Unit, DomainError>

    public suspend fun cancelled(
        sagaId: String,
        orderId: String,
        outcome: CancelOutcome,
    ): Result<Unit, DomainError>
}
