package io.eia.batchetl.application.port.outbound

import io.eia.batchetl.domain.OrderEvent
import io.eia.batchetl.domain.OrderRecord
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.jvm.JvmInline

/** 取り込みの位置(adapters が決める形。例 トピック・パーティションごとの次のオフセット)。 */
@JvmInline
public value class SourcePosition(
    public val value: String,
)

/**
 * 読んだイベントの塊。
 *
 * @property next この塊を取り込んだ後の位置
 * @property deadLettered 読めずに DLQ に送った件数(この塊の位置に含まれる)
 */
public data class EventBatch(
    val events: List<OrderEvent>,
    val next: SourcePosition,
    val deadLettered: Int,
)

/** 注文のイベントの読み取り(Kafka)。 */
public interface OrderEventSource {
    /**
     * [start] から(null なら最初から)、呼んだ時点の末尾までを塊ごとに [load] に渡す。[load] が Err を返したら、そこで止めて返す
     * (その塊の位置は進めない)。
     */
    public suspend fun drain(
        start: SourcePosition?,
        load: suspend (EventBatch) -> Result<Unit, DomainError>,
    ): Result<Unit, DomainError>
}

/** 取り込んだ行と位置の保存先(分析の DB の raw)。 */
public interface RawOrderStore {
    /** 前回までに取り込んだ位置。まだなければ null。 */
    public suspend fun position(): Result<SourcePosition?, DomainError>

    /**
     * [records] と [position] を同じトランザクションで確定する。行は冪等(同じイベントは 1 回だけ)。
     * データが変わった業務の日に、作り直しの印を付ける。
     */
    public suspend fun append(
        records: List<OrderRecord>,
        position: SourcePosition,
    ): Result<Unit, DomainError>
}
