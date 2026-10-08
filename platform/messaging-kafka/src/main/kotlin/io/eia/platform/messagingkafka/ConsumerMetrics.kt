package io.eia.platform.messagingkafka

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * [EventConsumer] のメトリクス(ADR-0028 §5)。OTLP → Collector → Prometheus(`eia_consumer_messages_total` など)。
 * 遅れ(lag)は Kafka 側(kafka-exporter の `kafka_consumergroup_lag`)で見る。
 *
 * | 名前 | 種類 | 属性 |
 * |---|---|---|
 * | `eia.consumer.messages` | counter | `messaging.destination.name`・`messaging.consumer.group.name`・`outcome`(`processed` / `duplicate` / `dead_lettered`) |
 * | `eia.consumer.dead_letters` | counter | `messaging.destination.name`・`messaging.consumer.group.name`・`reason`(DLQ の `eiaf.dlq.reason`) |
 * | `eia.consumer.retries` | counter | `messaging.destination.name`・`messaging.consumer.group.name`・`error.code`(Transient のその場のリトライ) |
 * | `eia.consumer.unavailable` | counter | `messaging.consumer.group.name`・`error.code`(Unavailable で読み直した回数) |
 * | `eia.consumer.process.duration` | histogram(秒) | `messaging.destination.name`・`messaging.consumer.group.name` |
 */
public class ConsumerMetrics(
    meter: Meter,
) {
    private val messages: LongCounter =
        meter
            .counterBuilder("eia.consumer.messages")
            .setDescription("処理を終えたメッセージの件数(結果ごと)")
            .setUnit("{message}")
            .build()
    private val deadLetters: LongCounter =
        meter
            .counterBuilder("eia.consumer.dead_letters")
            .setDescription("DLQ に送ったメッセージの件数(原因ごと)")
            .setUnit("{message}")
            .build()
    private val retries: LongCounter =
        meter
            .counterBuilder("eia.consumer.retries")
            .setDescription("一時的な失敗(Transient)で、その場でリトライした回数")
            .setUnit("{retry}")
            .build()
    private val unavailable: LongCounter =
        meter
            .counterBuilder("eia.consumer.unavailable")
            .setDescription("基盤が使えない(Unavailable)ため、最後のコミットの位置から読み直した回数")
            .setUnit("{retry}")
            .build()
    private val duration: DoubleHistogram =
        meter
            .histogramBuilder("eia.consumer.process.duration")
            .setDescription("1 件の処理(リトライ・DLQ への送信を含む)の時間")
            .setUnit("s")
            .build()

    internal fun handled(
        topic: String,
        group: String,
        outcome: Handled,
    ) = messages.add(1, Attributes.of(DESTINATION, topic, GROUP, group, OUTCOME, outcome.name.lowercase()))

    internal fun deadLettered(
        topic: String,
        group: String,
        reason: String,
    ) {
        messages.add(1, Attributes.of(DESTINATION, topic, GROUP, group, OUTCOME, DEAD_LETTERED))
        deadLetters.add(1, Attributes.of(DESTINATION, topic, GROUP, group, REASON, reason))
    }

    internal fun retried(
        topic: String,
        group: String,
        errorCode: String,
    ) = retries.add(1, Attributes.of(DESTINATION, topic, GROUP, group, ERROR_CODE, errorCode))

    internal fun unavailable(
        group: String,
        errorCode: String,
    ) = unavailable.add(1, Attributes.of(GROUP, group, ERROR_CODE, errorCode))

    internal fun processed(
        topic: String,
        group: String,
        seconds: Double,
    ) = duration.record(seconds, Attributes.of(DESTINATION, topic, GROUP, group))

    internal companion object {
        const val DEAD_LETTERED = "dead_lettered"
        private val DESTINATION = AttributeKey.stringKey("messaging.destination.name")
        private val GROUP = AttributeKey.stringKey("messaging.consumer.group.name")
        private val OUTCOME = AttributeKey.stringKey("outcome")
        private val REASON = AttributeKey.stringKey("reason")
        private val ERROR_CODE = AttributeKey.stringKey("error.code")
    }
}
