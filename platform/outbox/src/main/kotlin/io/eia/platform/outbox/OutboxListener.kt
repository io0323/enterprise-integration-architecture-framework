package io.eia.platform.outbox

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/** [Outbox] の追記の結果の通知。メトリクス([OutboxMetrics])に使う。 */
public interface OutboxListener {
    /** [records] を追記した(トランザクションの確定の前。業務が取り消されれば発行されない)。 */
    public fun appended(records: List<OutboxRecord>) {}

    public fun failed(error: OutboxError) {}

    public companion object {
        public val NONE: OutboxListener = object : OutboxListener {}
    }
}

/**
 * Outbox のメトリクス。`platform/observability` の `ObservabilityRuntime.meter` を渡して作る。
 *
 * | メトリクス | 種類 | 属性 |
 * |---|---|---|
 * | `eia.outbox.appended` | counter | `messaging.destination.name`(トピック) |
 * | `eia.outbox.append.failures` | counter | `error.code`(`outbox_misuse` / `outbox_storage_unavailable` / `outbox_storage_rejected`) |
 *
 * 追記の件数は確定の前に数える(業務が取り消された分も含む)。実際に発行された件数は Kafka 側(④ の監視)で見る。
 */
public class OutboxMetrics(
    meter: Meter,
) : OutboxListener {
    private val appended: LongCounter =
        meter
            .counterBuilder("eia.outbox.appended")
            .setUnit("{event}")
            .setDescription("Outbox に追記したイベントの件数")
            .build()
    private val failures: LongCounter =
        meter
            .counterBuilder("eia.outbox.append.failures")
            .setUnit("{append}")
            .setDescription("Outbox への追記の失敗の件数")
            .build()

    override fun appended(records: List<OutboxRecord>) {
        records.groupingBy { it.topic.name }.eachCount().forEach { (topic, count) ->
            appended.add(count.toLong(), Attributes.of(DESTINATION, topic))
        }
    }

    override fun failed(error: OutboxError) {
        failures.add(1, Attributes.of(ERROR_CODE, error.code))
    }

    private companion object {
        val DESTINATION: AttributeKey<String> = AttributeKey.stringKey("messaging.destination.name")
        val ERROR_CODE: AttributeKey<String> = AttributeKey.stringKey("error.code")
    }
}
