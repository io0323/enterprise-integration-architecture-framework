package io.eia.b2bgateway.adapters.order

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableDoubleGauge
import io.opentelemetry.api.metrics.ObservableLongGauge
import java.time.Instant

/**
 * 社内への取り込みの送信待ちの状態(取引先ごと)。[deadlines] は送信待ちの注文の業務の応答の期限のうちいちばん早いもの、
 * [expired] は直近に期限切れにした注文の数(`JdbcOrderSubmissions.snapshot`)。
 */
public data class SubmissionSnapshot(
    val deadlines: Map<String, Instant>,
    val expired: Map<String, Long>,
)

/**
 * 社内への取り込みのメトリクス(ADR-0040 §6・ADR-0041 §5。P12 の承認の条件 1)。アラートは `prometheus/rules/b2b.rules.yml`、手順は
 * docs/runbooks/b2b-partner-credentials.md。
 *
 * | 名前 | 種類 | 単位・意味 |
 * |---|---|---|
 * | `eia.b2b.order_submission.aperak_deadline` | gauge | 秒(UNIX 秒)。送信待ちの注文の、業務の応答(APERAK)の期限のうちいちばん早いもの。送信待ちがなければ系列がない |
 * | `eia.b2b.order_submission.expired` | gauge | 件。直近 24 時間に、送り直しの上限を過ぎて期限切れにした注文の数(運用者の手の対応が要る) |
 *
 * 属性は `trading_partner_id`。値は読み出しのたびに [snapshot] から取る(app が DB から定期的に作り直す)。
 */
public class OrderSubmissionMetrics(
    meter: Meter,
    private val snapshot: () -> SubmissionSnapshot,
) : AutoCloseable {
    private val deadline: ObservableDoubleGauge =
        meter
            .gaugeBuilder(DEADLINE)
            .setUnit("s")
            .setDescription("送信待ちの注文の業務の応答(APERAK)の期限のうちいちばん早いもの(UNIX 秒)")
            .buildWithCallback { measurement ->
                snapshot().deadlines.forEach { (partner, at) ->
                    measurement.record(at.epochSecond.toDouble(), Attributes.of(PARTNER, partner))
                }
            }

    private val expired: ObservableLongGauge =
        meter
            .gaugeBuilder(EXPIRED)
            .ofLongs()
            .setUnit("{submission}")
            .setDescription("直近 24 時間に、送り直しの上限を過ぎて期限切れにした注文の数")
            .buildWithCallback { measurement ->
                snapshot().expired.forEach { (partner, count) -> measurement.record(count, Attributes.of(PARTNER, partner)) }
            }

    override fun close() {
        deadline.close()
        expired.close()
    }

    public companion object {
        public const val DEADLINE: String = "eia.b2b.order_submission.aperak_deadline"
        public const val EXPIRED: String = "eia.b2b.order_submission.expired"
        private val PARTNER: AttributeKey<String> = AttributeKey.stringKey("trading_partner_id")
    }
}
