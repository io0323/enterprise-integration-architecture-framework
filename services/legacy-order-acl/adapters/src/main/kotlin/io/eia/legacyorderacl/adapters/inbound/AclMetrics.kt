package io.eia.legacyorderacl.adapters.inbound

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * ACL のメトリクス(ADR-0026 §7・§10)。OTLP → Collector → Prometheus(`eia_acl_records_total` など)。
 *
 * | 名前 | 種類 | 属性 |
 * |---|---|---|
 * | `eia.acl.records` | counter | `outcome`(`upserted` / `deleted` / `dead_lettered`) |
 * | `eia.acl.dead_letters` | counter | `reason`(DLQ の `eiaf.dlq.reason`) |
 * | `eia.acl.retries` | counter | `error.code`(一時的な失敗で読み直した回数) |
 */
public class AclMetrics(
    meter: Meter,
) {
    private val records: LongCounter =
        meter
            .counterBuilder("eia.acl.records")
            .setDescription("ACL が処理した変更の件数(結果ごと)")
            .setUnit("{record}")
            .build()
    private val deadLetters: LongCounter =
        meter
            .counterBuilder("eia.acl.dead_letters")
            .setDescription("ACL が DLQ に送った変更の件数(原因ごと)")
            .setUnit("{record}")
            .build()
    private val retries: LongCounter =
        meter
            .counterBuilder("eia.acl.retries")
            .setDescription("一時的な失敗で、最後のコミットの位置から読み直した回数")
            .setUnit("{retry}")
            .build()

    internal fun recorded(outcome: String) = records.add(1, Attributes.of(OUTCOME, outcome))

    internal fun deadLettered(reason: String) {
        records.add(1, Attributes.of(OUTCOME, DEAD_LETTERED))
        deadLetters.add(1, Attributes.of(REASON, reason))
    }

    internal fun retried(errorCode: String) = retries.add(1, Attributes.of(ERROR_CODE, errorCode))

    internal companion object {
        const val DEAD_LETTERED = "dead_lettered"
        private val OUTCOME = AttributeKey.stringKey("outcome")
        private val REASON = AttributeKey.stringKey("reason")
        private val ERROR_CODE = AttributeKey.stringKey("error.code")
    }
}
