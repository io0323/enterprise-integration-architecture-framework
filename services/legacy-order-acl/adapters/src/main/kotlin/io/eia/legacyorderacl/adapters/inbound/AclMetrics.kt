package io.eia.legacyorderacl.adapters.inbound

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * ACL のメトリクス(ADR-0026 §7・§10)。OTLP → Collector → Prometheus(`eia_acl_records_total`)。
 *
 * | 名前 | 種類 | 属性 |
 * |---|---|---|
 * | `eia.acl.records` | counter | `outcome`(`upserted` / `deleted`。変換して発行した件数) |
 *
 * DLQ と読み直しの件数は、読み取りの共通部品のメトリクス(`eia.consumer.dead_letters{reason}`・`eia.consumer.unavailable{error.code}`。
 * Consumer Group `legacy-order-acl.translate`。ADR-0028)で見る。
 */
public class AclMetrics(
    meter: Meter,
) {
    private val records: LongCounter =
        meter
            .counterBuilder("eia.acl.records")
            .setDescription("ACL が変換して発行した変更の件数(結果ごと)")
            .setUnit("{record}")
            .build()

    internal fun recorded(outcome: String) = records.add(1, Attributes.of(OUTCOME, outcome))

    private companion object {
        val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
    }
}
