package io.eia.legacyorderacl.adapters.inbound

import io.eia.legacyorderacl.application.port.inbound.LegacyOrderChange
import io.eia.legacyorderacl.application.port.inbound.TranslateLegacyOrderChangeUseCase
import io.eia.legacyorderacl.domain.TranslationError
import io.eia.legacyorderacl.domain.TranslationFailure
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.DeadLetterReason
import io.eia.platform.messagingkafka.MalformedEventPayload
import io.eia.platform.messagingkafka.SchemaUnavailable
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.observability.context.withSpan
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.mapError
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory

/**
 * 生の CDC の 1 レコードを処理する(ADR-0026 §6・§7)。
 *
 * - 変更ごとに新しいトレースと Correlation ID を始める(レガシーはトレースを持たない)。CONSUMER の span の下で、発行の PRODUCER の span を作る。
 * - 読めない・変換できない(NonRetryable)レコードは、リトライせずに DLQ に送り、[Processed] を返す(本流を止めない)。
 * - 一時的な失敗(Retryable。Kafka・Apicurio)は [RetryLater] を返す(呼び出し元が最後のコミットの位置から読み直す)。
 *   DLQ に送れないときも [RetryLater](DLQ に入る前にオフセットを進めない)。
 */
public class LegacyChangeProcessor(
    private val translate: TranslateLegacyOrderChangeUseCase,
    writerSchemas: WriterSchemas,
    private val deadLetters: DeadLetterPublisher,
    private val observability: ObservabilityRuntime,
    private val metrics: AclMetrics,
) {
    private val deserializer = AvroEventDeserializer(RawJuchuEnvelope.serializer(), writerSchemas)

    public sealed interface Outcome

    /** 処理を終えた(発行した、または DLQ に送った)。オフセットを進めてよい。 */
    public data object Processed : Outcome

    /** 一時的な失敗。オフセットを進めず、読み直す。 */
    public data class RetryLater(
        val error: DomainError,
    ) : Outcome

    /** 一時的な失敗で読み直したことを数える(呼び出し元の [LegacyChangeConsumer] が呼ぶ)。 */
    internal fun retried(errorCode: String) = metrics.retried(errorCode)

    public suspend fun process(record: ConsumerRecord<ByteArray?, ByteArray?>): Outcome =
        withContext(ObservabilityContext(CorrelationId.generate(), INTEGRATION_ID, Context.root())) {
            observability.withSpan("${record.topic()} process", SpanKind.CONSUMER) { span ->
                span.setAttribute(MESSAGING_SYSTEM, KAFKA)
                span.setAttribute(MESSAGING_DESTINATION, record.topic())
                span.setAttribute(MESSAGING_OFFSET, record.offset())
                when (val result = decode(record).flatMap { change -> translate(change) }) {
                    is Result.Ok -> {
                        metrics.recorded(result.value.name.lowercase())
                        Processed
                    }

                    is Result.Err -> {
                        span.setAttribute(ERROR_TYPE, result.error.code)
                        handleFailure(record, result.error)
                    }
                }
            }
        }

    private suspend fun decode(record: ConsumerRecord<ByteArray?, ByteArray?>): Result<LegacyOrderChange, DomainError> {
        val value = record.value() ?: return err(TranslationError(TranslationFailure.UNDECODABLE, "-", "値がない(tombstone)"))
        return deserializer
            .deserialize(value)
            .mapError { error ->
                when (error) {
                    is MalformedEventPayload, is SchemaUnavailable.Permanent -> {
                        TranslationError(TranslationFailure.UNDECODABLE, "-", "Avro として読めない(${error.code})")
                    }

                    else -> {
                        error.asDomainError()
                    }
                }
            }.flatMap { envelope -> RawChangeMapper.toChange(envelope).mapError { it } }
    }

    private suspend fun handleFailure(
        record: ConsumerRecord<ByteArray?, ByteArray?>,
        error: DomainError,
    ): Outcome {
        if (error is DomainError.Retryable) return RetryLater(error)
        val reason =
            when (error) {
                is TranslationError -> DeadLetterReason(error.failure.name, error.message)

                // 変換の後の失敗(契約と実装の食い違い・送信の拒否)。原因の種類はエラーのコード
                else -> DeadLetterReason(error.code.uppercase(), error.message)
            }
        return when (val sent = deadLetters.send(record, reason)) {
            is Result.Ok -> {
                metrics.deadLettered(reason.code)
                logger.warn(
                    "変換できない変更を DLQ に送りました(reason={}, {} partition={} offset={})",
                    reason.code,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                )
                Processed
            }

            is Result.Err -> {
                logger.error("DLQ に送れません({})。オフセットを進めずに読み直します", sent.error.code)
                RetryLater(sent.error.asDomainError())
            }
        }
    }

    private companion object {
        const val INTEGRATION_ID = "INT-SALES-003"
        const val KAFKA = "kafka"
        val MESSAGING_SYSTEM: AttributeKey<String> = AttributeKey.stringKey("messaging.system")
        val MESSAGING_DESTINATION: AttributeKey<String> = AttributeKey.stringKey("messaging.destination.name")
        val MESSAGING_OFFSET: AttributeKey<Long> = AttributeKey.longKey("messaging.kafka.offset")
        val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")
        val logger = LoggerFactory.getLogger(LegacyChangeProcessor::class.java)
    }
}
