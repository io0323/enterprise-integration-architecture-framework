package io.eia.order.application.port.outbound

import io.eia.order.domain.Order
import io.eia.order.domain.SagaFailure
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 注文のイベントの発行(Outbox。Framework 8.3・ADR-0007)。adapters が `platform/outbox` で実装する
 * (application はイベントの形式・Kafka・Outbox の部品に依存しない)。
 *
 * 業務の更新と**同じトランザクションの中**で呼ぶ。Outbox に書けなければ、業務の更新も取り消す(イベントのない業務の更新を残さない)。
 * 業務の更新が取り消されれば、イベントも発行されない(二重書き込みの問題が起きない)。発行そのものは Debezium が確定の後に行う。
 */
public interface OrderEventOutbox {
    /** 注文を受け付けたこと(`sales.order.created.v1`)を書く。 */
    public suspend fun orderPlaced(order: Order): Result<Unit, DomainError>

    /** 注文を取り消したこと(`sales.order.cancelled.v1`)を書く。[reason] は補償の理由(ADR-0029 §8)。 */
    public suspend fun orderCancelled(
        order: Order,
        reason: SagaFailure?,
    ): Result<Unit, DomainError>
}
