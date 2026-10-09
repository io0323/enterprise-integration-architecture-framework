package io.eia.inventory.adapters.inbound

import io.eia.inventory.adapters.out.persistence.TransientSqlError
import io.eia.inventory.application.port.inbound.CommandEnvelope
import io.eia.inventory.application.port.inbound.CommandOutcome
import io.eia.inventory.application.port.inbound.ReleaseStockUseCase
import io.eia.inventory.application.port.inbound.ReserveStockUseCase
import io.eia.inventory.domain.StockLine
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.ConsumedEvent
import io.eia.platform.messagingkafka.EventSubscription
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.messagingkafka.Handled
import io.eia.platform.messagingkafka.HandlingFailure
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok

/**
 * inventory のコマンド(INT-INVENTORY-001)の購読。Consumer Group は `inventory.command` だけ(ADR-0006)。
 *
 * ユースケースのエラーを、Consumer の失敗の種類に写す(ADR-0028 §2):
 * - [TransientSqlError](直列化の失敗・デッドロック・同時の挿入)→ Transient(その場でリトライ)
 * - そのほかの Retryable(DB に接続できない・Outbox に書けない)→ Unavailable(DLQ に送らず読み直す)
 * - NonRetryable(値の誤り・実装の誤り)→ Rejected(DLQ)
 */
public class InventoryCommandHandlers(
    private val reserve: ReserveStockUseCase,
    private val release: ReleaseStockUseCase,
) {
    public fun subscriptions(writerSchemas: WriterSchemas): List<EventSubscription<*>> =
        listOf(
            EventSubscription(RESERVE, AvroEventDeserializer(ReserveStockV1.serializer(), writerSchemas), INTEGRATION_ID, ::onReserve),
            EventSubscription(RELEASE, AvroEventDeserializer(ReleaseStockV1.serializer(), writerSchemas), INTEGRATION_ID, ::onRelease),
        )

    internal suspend fun onReserve(event: ConsumedEvent<ReserveStockV1>): Result<Handled, HandlingFailure> =
        lines(event.value.lines)
            .flatMap { lines -> reserve(envelope(event, event.value.sagaId, event.value.orderId), lines) }
            .toHandling()

    internal suspend fun onRelease(event: ConsumedEvent<ReleaseStockV1>): Result<Handled, HandlingFailure> =
        release(envelope(event, event.value.sagaId, event.value.orderId)).toHandling()

    private fun envelope(
        event: ConsumedEvent<*>,
        sagaId: String,
        orderId: String,
    ) = CommandEnvelope(event.metadata.id.toString(), event.topic, sagaId, orderId)

    private fun lines(lines: List<StockLineV1>): Result<List<StockLine>, DomainError> {
        if (lines.isEmpty()) return err(ValidationError(listOf(FieldViolation("lines", "1 件以上にしてください"))))
        val parsed = lines.map { StockLine.of(it.lineNumber, it.sku, it.quantity) }
        val violations = parsed.filterIsInstance<Result.Err<ValidationError>>().flatMap { it.error.violations }.distinct()
        return if (violations.isEmpty()) ok(parsed.map { (it as Result.Ok).value }) else err(ValidationError(violations))
    }

    public companion object {
        public val RESERVE: EventTopic = EventTopic.of("inventory.stock.cmd-reserve.v1")
        public val RELEASE: EventTopic = EventTopic.of("inventory.stock.cmd-release.v1")
        public const val GROUP_ID: String = "inventory.command"
        public const val INTEGRATION_ID: String = "INT-INVENTORY-001"

        internal fun Result<CommandOutcome, DomainError>.toHandling(): Result<Handled, HandlingFailure> =
            map { outcome ->
                when (outcome) {
                    CommandOutcome.PROCESSED -> Handled.PROCESSED
                    CommandOutcome.DUPLICATE -> Handled.DUPLICATE
                }
            }.mapError { error ->
                when (error) {
                    is TransientSqlError -> HandlingFailure.Transient(error.code, error.message)
                    is DomainError.Retryable -> HandlingFailure.Unavailable(error.code, error.message)
                    is DomainError.NonRetryable -> HandlingFailure.Rejected(error.code, error.message)
                }
            }
    }
}
