package io.eia.platform.messagingkafka

import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.observability.context.withSpan
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RebalanceInProgressException
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.slf4j.LoggerFactory
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * 型付きの Consumer(Framework 6.4・6.5・13.2。ADR-0028)。購読したトピックを読み、1 件ずつ処理して、処理を終えたオフセットだけをコミットする。
 *
 * - **At-Least-Once**: 処理(業務の更新と冪等消費の記録。platform/inbox)の確定の後にコミットする。コミットの前に落ちれば、
 *   最後のコミットの位置から送り直す(重複は受信側の冪等で吸収する)。
 * - **順序**: 1 つの Consumer(1 つのスレッド)で、パーティションごとに届いた順に処理する。同じキー(集約の ID・Saga ID)の順序を保つ。
 * - **失敗の扱い**([HandlingFailure]):
 *   - ヘッダ(CloudEvents)の欠落・値を読めない・想定しない tombstone・[HandlingFailure.Rejected] は、リトライせずに DLQ(本流を止めない)。
 *   - [HandlingFailure.Transient] は、その場でリトライし([handlerRetry]。既定は初回 + 3 回)、尽きたら DLQ。
 *   - [HandlingFailure.Unavailable]・Schema Registry の一時的な失敗・DLQ に送れないときは、DLQ に送らず、処理を終えた分までを
 *     コミットして、未処理の位置から Backoff の後に読み直す。その間 [ready] は false(`/health/ready` が 503)で、lag が増える。
 * - **追跡**: CONSUMER の span(`{topic} process`)は、ヘッダの `traceparent` の子にする。Correlation ID はヘッダの値を引き継ぐ。
 *   DLQ・処理の中の発行(Outbox)は、この span の下になる。
 * - ログ・span・DLQ のヘッダに値は入れない(CLAUDE.md §5 可観測性)。
 *
 * Kafka の Consumer はスレッドセーフでないので、[run] は 1 つのスレッドの Dispatcher で動かす(app が用意する)。
 *
 * @param groupId Consumer の `group.id`([consumerProperties] に渡したもの)。メトリクス・冪等消費の記録のキーに使う
 */
@Suppress("LongParameterList") // 差し替えるのはテストだけ(既定値あり)
public class EventConsumer(
    private val consumer: Consumer<ByteArray?, ByteArray?>,
    private val groupId: String,
    subscriptions: List<EventSubscription<*>>,
    private val deadLetters: DeadLetterPublisher,
    private val observability: ObservabilityRuntime,
    private val metrics: ConsumerMetrics = ConsumerMetrics(observability.meter),
    private val handlerRetry: RetryPolicy = HANDLER_RETRY,
    private val unavailableRetry: RetryPolicy = UNAVAILABLE_RETRY,
    private val committer: OffsetCommitter = OffsetCommitter { c, offsets -> c.commitSync(offsets) },
    private val pollTimeout: Duration = DEFAULT_POLL_TIMEOUT,
    private val random: Random = Random.Default,
) {
    private val subscriptions: Map<String, EventSubscription<*>> = subscriptions.associateBy { it.topic.name }
    private val propagator = observability.openTelemetry.propagators.textMapPropagator

    init {
        require(subscriptions.isNotEmpty()) { "購読するトピックがありません" }
        require(this.subscriptions.size == subscriptions.size) { "同じトピックを 2 回購読しています" }
        require(GROUP_ID.matches(groupId)) { "groupId は {service}.{purpose} の形にしてください: $groupId" }
    }

    @Volatile
    private var healthy = false

    /**
     * 読み取りを始めていて、直近の処理が Unavailable でない。パーティションの割り当ては条件にしない
     * (割り当ての待ち・リバランスの間も正常。遅れは lag の監視で見る)。
     */
    public val ready: Boolean get() = healthy

    /** 購読し、キャンセルされるまで処理を続ける。終わるときに Consumer を閉じる(同じスレッドで)。 */
    public suspend fun run() {
        try {
            consumer.subscribe(subscriptions.keys.toList())
            healthy = true
            var failures = 0
            while (currentCoroutineContext().isActive) {
                val failure = pollOnce()
                if (failure == null) {
                    failures = 0
                    healthy = true
                } else {
                    failures++
                    healthy = false
                    val wait = unavailableRetry.backoff(failures, random)
                    logger.warn("基盤が使えないため、{} の後に未処理の位置から読み直します(group={}, {}。{} 回目)", wait, groupId, failure, failures)
                    delay(wait)
                }
            }
        } finally {
            healthy = false
            consumer.close()
        }
    }

    /** 1 回 poll して処理する。Unavailable のときはそのエラーのコード、それ以外は null。 */
    internal suspend fun pollOnce(): String? {
        val records = consumer.poll(pollTimeout.toJavaDuration())
        val processed = mutableMapOf<TopicPartition, OffsetAndMetadata>()
        // パーティションごとに、届いた順に処理する
        for (record in records.partitions().flatMap { records.records(it) }) {
            val outcome = process(record)
            if (outcome is Outcome.RetryLater) {
                commit(processed)
                // このバッチの各パーティションを、未処理の最初の位置に戻す(処理済みの分は送り直さない)
                records.partitions().forEach { p -> consumer.seek(p, processed[p]?.offset() ?: records.records(p).first().offset()) }
                metrics.unavailable(groupId, outcome.code)
                return outcome.code
            }
            processed[TopicPartition(record.topic(), record.partition())] = OffsetAndMetadata(record.offset() + 1)
        }
        commit(processed)
        return null
    }

    private fun commit(offsets: Map<TopicPartition, OffsetAndMetadata>) {
        if (offsets.isEmpty()) return
        try {
            committer.commit(consumer, offsets)
        } catch (e: CommitFailedException) {
            // 処理が長くグループから外された・リバランスの途中。新しい割り当て先が最後のコミットから送り直す(重複は冪等で吸収する)
            logger.warn("オフセットをコミットできません(group={}, {})。送り直しは冪等で吸収します", groupId, e::class.simpleName)
        } catch (e: RebalanceInProgressException) {
            logger.warn("オフセットをコミットできません(group={}, {})。送り直しは冪等で吸収します", groupId, e::class.simpleName)
        }
    }

    private sealed interface Outcome {
        /** 処理を終えた(業務の処理・重複・DLQ)。オフセットを進めてよい。 */
        data object Done : Outcome

        /** 基盤が使えない。オフセットを進めず、読み直す。 */
        data class RetryLater(
            val code: String,
        ) : Outcome
    }

    private suspend fun process(record: ConsumerRecord<ByteArray?, ByteArray?>): Outcome {
        val subscription = requireNotNull(subscriptions[record.topic()]) { "購読していないトピックです: ${record.topic()}" }
        val started = TimeSource.Monotonic.markNow()
        val metadata = EventMetadata.fromHeaders(record.headers())
        val parent = propagator.extract(Context.root(), record.headers(), KafkaHeadersGetter)
        val correlationId = (metadata as? Result.Ok)?.value?.correlationId ?: CorrelationId.generate()
        return withContext(ObservabilityContext(correlationId, subscription.integrationId, parent)) {
            observability.withSpan("${record.topic()} process", SpanKind.CONSUMER) { span ->
                span.setAttribute(MESSAGING_SYSTEM, KAFKA)
                span.setAttribute(MESSAGING_DESTINATION, record.topic())
                span.setAttribute(MESSAGING_GROUP, groupId)
                span.setAttribute(MESSAGING_OPERATION, OPERATION_PROCESS)
                span.setAttribute(MESSAGING_PARTITION, record.partition().toString())
                span.setAttribute(MESSAGING_OFFSET, record.offset())
                val outcome =
                    when (metadata) {
                        is Result.Err -> {
                            val fields = metadata.error.violations.joinToString(", ") { "${it.field}: ${it.reason}" }
                            deadLetter(span, record, HandlingFailure.Rejected(INVALID_HEADERS, "ヘッダが不正です($fields)"), 1)
                        }

                        is Result.Ok -> {
                            span.setAttribute(MESSAGING_MESSAGE_ID, metadata.value.id.toString())
                            handle(span, subscription, record, metadata.value)
                        }
                    }
                metrics.processed(record.topic(), groupId, started.elapsedNow().inWholeMicroseconds / MICROS_PER_SECOND)
                outcome
            }
        }
    }

    private suspend fun <T> handle(
        span: Span,
        subscription: EventSubscription<T>,
        record: ConsumerRecord<ByteArray?, ByteArray?>,
        metadata: EventMetadata,
    ): Outcome {
        val payload =
            record.value()
                ?: return deadLetter(span, record, HandlingFailure.Rejected(UNEXPECTED_TOMBSTONE, "値がない(tombstone)"), 1)
        val value =
            when (val decoded = subscription.deserializer.deserialize(payload)) {
                is Result.Ok -> {
                    decoded.value
                }

                is Result.Err -> {
                    return when (val error = decoded.error) {
                        is SchemaUnavailable.Temporary -> unavailable(span, HandlingFailure.Unavailable(error.code, error.message))
                        else -> deadLetter(span, record, HandlingFailure.Rejected(UNDECODABLE, "値を読めません(${error.code})"), 1)
                    }
                }
            }
        val event = ConsumedEvent(metadata, record.key()?.toString(Charsets.UTF_8), value, record.topic(), record.partition(), record.offset())
        var attempt = 1
        while (true) {
            when (val result = invoke(subscription.handler, event)) {
                is Result.Ok -> {
                    metrics.handled(record.topic(), groupId, result.value)
                    return Outcome.Done
                }

                is Result.Err -> {
                    when (val failure = result.error) {
                        is HandlingFailure.Unavailable -> {
                            return unavailable(span, failure)
                        }

                        is HandlingFailure.Rejected -> {
                            return deadLetter(span, record, failure, attempt)
                        }

                        is HandlingFailure.Transient -> {
                            if (attempt >= handlerRetry.maxAttempts) return deadLetter(span, record, failure, attempt)
                            metrics.retried(record.topic(), groupId, failure.code)
                            delay(handlerRetry.backoff(attempt, random))
                            attempt++
                        }
                    }
                }
            }
        }
    }

    /** 処理の想定しない例外(実装の誤り)は Transient として扱う(リトライが尽きれば DLQ に隔離し、本流を止めない)。 */
    @Suppress("TooGenericExceptionCaught") // 想定しない例外も DLQ に隔離するため。キャンセルは再送出する
    private suspend fun <T> invoke(
        handler: EventHandler<T>,
        event: ConsumedEvent<T>,
    ): Result<Handled, HandlingFailure> =
        try {
            handler.handle(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val type = e::class.simpleName ?: "unknown"
            // 例外のメッセージは値を含みうるため、型の名前だけを残す
            logger.error("処理が想定しない例外を投げました({}。{} partition={} offset={})", type, event.topic, event.partition, event.offset)
            Result.Err(HandlingFailure.Transient(UNEXPECTED_EXCEPTION, "処理が例外を投げました($type)"))
        }

    private fun unavailable(
        span: Span,
        failure: HandlingFailure.Unavailable,
    ): Outcome {
        span.setAttribute(ERROR_TYPE, failure.code)
        return Outcome.RetryLater(failure.code)
    }

    private suspend fun deadLetter(
        span: Span,
        record: ConsumerRecord<ByteArray?, ByteArray?>,
        failure: HandlingFailure,
        attempts: Int,
    ): Outcome {
        span.setAttribute(ERROR_TYPE, failure.code)
        val reason = DeadLetterReason(failure.code.uppercase(), failure.detail)
        return when (val sent = deadLetters.send(record, reason, attempts)) {
            is Result.Ok -> {
                metrics.deadLettered(record.topic(), groupId, reason.code)
                logger.warn(
                    // detail は項目の名前と破った規則だけで、値を含まない(DLQ のヘッダと同じ)
                    "処理できないメッセージを DLQ に送りました(reason={}, detail={}, attempts={}, {} partition={} offset={})",
                    reason.code,
                    reason.detail,
                    attempts,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                )
                Outcome.Done
            }

            is Result.Err -> {
                logger.error("DLQ に送れません({})。オフセットを進めずに読み直します", sent.error.code)
                Outcome.RetryLater(sent.error.code)
            }
        }
    }

    public companion object {
        /** Transient のリトライ: 初回 + 3 回(Framework 6.5 の既定)。Backoff + Jitter(INTEGRATION_STANDARDS §3)。 */
        public val HANDLER_RETRY: RetryPolicy = RetryPolicy(maxAttempts = 4)

        /** Unavailable の読み直し: 回数の上限なし。待ちは最大 30 秒(`max.poll.interval.ms` の既定の 5 分より十分に短い)。 */
        public val UNAVAILABLE_RETRY: RetryPolicy = RetryPolicy(maxAttempts = Int.MAX_VALUE)

        /** DLQ の `eiaf.dlq.reason`(ADR-0028 §2)。処理が返す [HandlingFailure.code] は大文字にして使う。 */
        public const val INVALID_HEADERS: String = "INVALID_HEADERS"
        public const val UNDECODABLE: String = "UNDECODABLE"
        public const val UNEXPECTED_TOMBSTONE: String = "UNEXPECTED_TOMBSTONE"
        public const val UNEXPECTED_EXCEPTION: String = "UNEXPECTED_EXCEPTION"

        private val GROUP_ID = Regex("^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*$")
        private val DEFAULT_POLL_TIMEOUT = 500.milliseconds
        private const val MAX_POLL_RECORDS = 100
        private const val MICROS_PER_SECOND = 1_000_000.0
        private const val KAFKA = "kafka"
        private const val OPERATION_PROCESS = "process"
        private val MESSAGING_SYSTEM: AttributeKey<String> = AttributeKey.stringKey("messaging.system")
        private val MESSAGING_DESTINATION: AttributeKey<String> = AttributeKey.stringKey("messaging.destination.name")
        private val MESSAGING_GROUP: AttributeKey<String> = AttributeKey.stringKey("messaging.consumer.group.name")
        private val MESSAGING_OPERATION: AttributeKey<String> = AttributeKey.stringKey("messaging.operation.type")
        private val MESSAGING_MESSAGE_ID: AttributeKey<String> = AttributeKey.stringKey("messaging.message.id")
        private val MESSAGING_PARTITION: AttributeKey<String> = AttributeKey.stringKey("messaging.destination.partition.id")
        private val MESSAGING_OFFSET: AttributeKey<Long> = AttributeKey.longKey("messaging.kafka.offset")
        private val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")
        private val logger = LoggerFactory.getLogger(EventConsumer::class.java)

        /**
         * Consumer の設定。オフセットは処理の後に明示的にコミットする(`enable.auto.commit=false`)。初めて読むときはトピックの最初から読む。
         *
         * @param groupId `{service}.{purpose}`(INTEGRATION_STANDARDS §1)。コマンドのトピックは受信サービスの `{service}.command` だけ(ADR-0006)
         */
        public fun consumerProperties(
            bootstrapServers: String,
            groupId: String,
            clientId: String,
        ): Map<String, Any> =
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to groupId,
                ConsumerConfig.CLIENT_ID_CONFIG to clientId,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to MAX_POLL_RECORDS,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            )
    }
}

/** 処理を終えたオフセットのコミット。統合テストで、処理の後・コミットの前に止める場合の差し替えに使う。 */
public fun interface OffsetCommitter {
    public fun commit(
        consumer: Consumer<ByteArray?, ByteArray?>,
        offsets: Map<TopicPartition, OffsetAndMetadata>,
    )
}

/** Kafka のヘッダから `traceparent`・`tracestate` を読む(同じ名前が複数あれば最後のもの)。 */
private object KafkaHeadersGetter : TextMapGetter<Headers> {
    override fun keys(carrier: Headers): Iterable<String> = carrier.map { it.key() }

    override fun get(
        carrier: Headers?,
        key: String,
    ): String? = carrier?.lastHeader(key)?.value()?.toString(Charsets.UTF_8)
}
