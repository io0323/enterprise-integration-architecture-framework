package io.eia.webhookreceiver.adapters.inbound.rest

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * Webhook の受け口のメトリクス(ADR-0035 §2・§3)。OTLP → Collector → Prometheus(`eia_webhook_requests_total`)。
 *
 * | 名前 | 種類 | 属性 |
 * |---|---|---|
 * | `eia.webhook.requests` | counter | `source`・`outcome`(下の表)・`reason`(拒否・失敗の理由のコード。なければ `none`)・`key`(下記) |
 *
 * `key` は一致した鍵(`current` / `previous`)。受け付けたときだけで、ほかは `none`。
 *
 * | outcome | 意味 | 応答 |
 * |---|---|---|
 * | `recorded` | 新しく記録し、Outbox に書いた | 204 |
 * | `duplicate` | 記録済みの ID の再送(本文も同じ) | 204 |
 * | `duplicate_body_mismatch` | 記録済みの ID で本文が違う(送り手の誤り。先の本文を正とする) | 204 |
 * | `rejected` | 署名・時刻・ヘッダ・大きさで拒否した(記録しない。`reason` は `webhook_signature_invalid` など) | 400 / 401 / 413 |
 * | `failed` | 鍵を読めない・記録を書けない(送り手に再送させる) | 503 / 500 |
 *
 * `key=previous` が続くなら、送り手がまだ新しい鍵に切り替えていない(鍵の差し替えの手順で、古い鍵を外す前に確かめる)。
 * 値(イベント ID・本文)は属性に入れない(カーディナリティと個人情報)。
 */
public class WebhookMetrics(
    meter: Meter,
) {
    private val requests: LongCounter =
        meter
            .counterBuilder("eia.webhook.requests")
            .setDescription("Webhook の受け口が受けた要求の件数")
            .setUnit("{request}")
            .build()

    public fun count(
        source: String,
        outcome: String,
        reason: String = NONE,
        key: String = NONE,
    ) {
        requests.add(1, Attributes.of(SOURCE, source, OUTCOME, outcome, REASON, reason, KEY, key))
    }

    public companion object {
        public const val NONE: String = "none"
        private val SOURCE: AttributeKey<String> = AttributeKey.stringKey("source")
        private val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
        private val REASON: AttributeKey<String> = AttributeKey.stringKey("reason")
        private val KEY: AttributeKey<String> = AttributeKey.stringKey("key")
    }
}
