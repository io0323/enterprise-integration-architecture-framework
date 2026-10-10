package io.eia.legacyorderacl.adapters.inbound

import io.eia.legacyorderacl.application.port.inbound.LegacyOrderChange
import io.eia.legacyorderacl.application.port.inbound.TranslateLegacyOrderChangeUseCase
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.ConsumedRecord
import io.eia.platform.messagingkafka.EventConsumer
import io.eia.platform.messagingkafka.ExternalSubscription
import io.eia.platform.messagingkafka.Handled
import io.eia.platform.messagingkafka.HandlingFailure
import io.eia.platform.messagingkafka.RecordHandler
import io.eia.platform.messagingkafka.Subscription
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError

/**
 * 生の CDC の 1 レコードを変換して発行する(ADR-0026 §6・§7)。読み取り・コミット・DLQ・読み直しは
 * `platform/messaging-kafka` の [EventConsumer](外部のトピックの購読 [ExternalSubscription]。ADR-0028)が行う。
 *
 * - 変更ごとに新しいトレースと Correlation ID を始める(レガシーはトレースを持たない。[ExternalSubscription] の既定)。
 * - 読めない値・変換できない値(NonRetryable)は、リトライせずに DLQ に送る([HandlingFailure.Rejected]。本流を止めない)。
 *   原因(`eiaf.dlq.reason`)は [io.eia.legacyorderacl.domain.TranslationFailure] の名前。
 * - 一時的な失敗(Retryable。Kafka・Apicurio)は、DLQ に送らずに最後のコミットの位置から読み直す([HandlingFailure.Unavailable]。ready=false)。
 * - 冪等消費の記録(processed_message)は使わない。状態を持たない変換で、出力は compacted のトピックの最新の状態なので、重複しても結果は同じ。
 */
public class LegacyChangeHandler(
    private val translate: TranslateLegacyOrderChangeUseCase,
    private val metrics: AclMetrics,
) {
    internal suspend fun handle(record: ConsumedRecord<RawJuchuEnvelope>): Result<Handled, HandlingFailure> {
        val change: Result<LegacyOrderChange, DomainError> = RawChangeMapper.toChange(record.value).mapError { it }
        return change
            .flatMap { translate(it) }
            .map { outcome ->
                metrics.recorded(outcome.name.lowercase())
                Handled.PROCESSED
            }.mapError { error ->
                when (error) {
                    is DomainError.Retryable -> HandlingFailure.Unavailable(error.code, error.message)
                    is DomainError.NonRetryable -> HandlingFailure.Rejected(error.code, error.message)
                }
            }
    }

    /** 生の CDC のトピックの購読。書き手のスキーマは contentId から取る([WriterSchemas]。ADR-0026 §4)。 */
    public fun subscription(writerSchemas: WriterSchemas): Subscription =
        ExternalSubscription(
            TOPIC,
            AvroEventDeserializer(RawJuchuEnvelope.serializer(), writerSchemas),
            INTEGRATION_ID,
            RecordHandler { handle(it) },
        )

    public companion object {
        public const val TOPIC: String = "_cdc.legacy.public.t_juchu"
        public const val GROUP_ID: String = "legacy-order-acl.translate"
        public const val CLIENT_ID: String = "legacy-order-acl"
        private const val INTEGRATION_ID = "INT-SALES-003"
    }
}
