package io.eia.order.application.usecase

import io.eia.order.application.port.inbound.HandleSagaReplyUseCase
import io.eia.order.application.port.inbound.ReplyOutcome
import io.eia.order.application.port.inbound.SagaReply
import io.eia.order.application.port.outbound.ProcessedReplies
import io.eia.order.application.port.outbound.SagaStore
import io.eia.order.application.port.outbound.TransactionRunner
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok

/**
 * 返信で Saga を進める。1 つのトランザクションで、冪等消費の記録 → Saga のロック → 判定 → 注文・Saga・次のコマンド・イベントの書き込み。
 * Saga の行をロックするので、期限切れの処理([TimeoutSagasService])と同じ Saga を同時に進めない(後の側は新しい状態で判定し直す)。
 * 知らない Saga ID の返信は [NotFoundError](DLQ)。
 */
public class HandleSagaReplyService(
    private val transactions: TransactionRunner,
    private val processed: ProcessedReplies,
    private val sagas: SagaStore,
    private val coordinator: SagaCoordinator,
) : HandleSagaReplyUseCase {
    override suspend fun invoke(reply: SagaReply): Result<ReplyOutcome, DomainError> =
        transactions.inTransaction {
            processed.markProcessed(reply.messageId, reply.topic).flatMap { first ->
                if (!first) {
                    ok(ReplyOutcome.DUPLICATE)
                } else {
                    sagas.findForUpdate(reply.sagaId).flatMap { saga ->
                        if (saga == null) {
                            err(NotFoundError("saga", reply.sagaId))
                        } else {
                            coordinator.handle(saga, reply.signal).map { ReplyOutcome.PROCESSED }
                        }
                    }
                }
            }
        }
}
