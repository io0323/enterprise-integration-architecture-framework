package io.eia.integrationflow.adapters

import io.eia.integrationflow.application.port.inbound.SyncReport
import io.eia.integrationflow.domain.VersionDecision
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * integration-flow のメトリクス(ADR-0036)。OTLP → Collector → Prometheus。属性は決まった値だけ(Flow の ID・経路・結果)。
 *
 * | 名前 | 種類 | 属性 |
 * |---|---|---|
 * | `eia.flow.records` | counter | `flow`(取り込まない種類は `none`)・`path`(`webhook` / `poll`)・`outcome`(下の表) |
 * | `eia.flow.sync.runs` | counter | `flow`・`outcome`(`succeeded` / `partial`(写せないレコードがあり Watermark を進めなかった) / `failed`) |
 *
 * | outcome | 意味 |
 * |---|---|
 * | `published` | 新しい版を出した |
 * | `same` / `stale` | 記録と同じ版・古い版(順序の逆転・再送・重なった同期)。出さない |
 * | `ignored` | どの Flow も取り込まない種類(例 `customer.deleted`) |
 * | `duplicate` | 処理済みの `ce_id`(Kafka の再送) |
 * | `failed` | 写せない・Canonical の検証を通らない(Webhook は DLQ。同期は数えて先に進む) |
 */
public class FlowMetrics(
    meter: Meter,
) {
    private val records: LongCounter =
        meter
            .counterBuilder("eia.flow.records")
            .setDescription("Flow が処理したレコードの件数")
            .setUnit("{record}")
            .build()
    private val syncRuns: LongCounter =
        meter
            .counterBuilder("eia.flow.sync.runs")
            .setDescription("定期の同期の回数")
            .setUnit("{run}")
            .build()

    public fun record(
        flow: String,
        path: String,
        outcome: String,
        count: Long = 1,
    ) {
        if (count > 0) records.add(count, Attributes.of(FLOW, flow, PATH, path, OUTCOME, outcome))
    }

    public fun decision(
        flow: String,
        path: String,
        decision: VersionDecision,
    ): Unit = record(flow, path, outcome(decision))

    public fun synced(
        flow: String,
        report: SyncReport?,
    ) {
        if (report != null) {
            record(flow, POLL, PUBLISHED, report.published.toLong())
            record(flow, POLL, SAME, report.same.toLong())
            record(flow, POLL, STALE, report.stale.toLong())
            record(flow, POLL, FAILED, report.failed.toLong())
        }
        val outcome =
            when {
                report == null -> "failed"
                report.failed > 0 -> "partial"
                else -> "succeeded"
            }
        syncRuns.add(1, Attributes.of(FLOW, flow, OUTCOME, outcome))
    }

    public companion object {
        public const val WEBHOOK: String = "webhook"
        public const val POLL: String = "poll"
        public const val NONE: String = "none"
        public const val PUBLISHED: String = "published"
        public const val SAME: String = "same"
        public const val STALE: String = "stale"
        public const val IGNORED: String = "ignored"
        public const val DUPLICATE: String = "duplicate"
        public const val FAILED: String = "failed"
        private val FLOW: AttributeKey<String> = AttributeKey.stringKey("flow")
        private val PATH: AttributeKey<String> = AttributeKey.stringKey("path")
        private val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")

        public fun outcome(decision: VersionDecision): String =
            when (decision) {
                VersionDecision.NEWER -> PUBLISHED
                VersionDecision.SAME -> SAME
                VersionDecision.STALE -> STALE
            }
    }
}
