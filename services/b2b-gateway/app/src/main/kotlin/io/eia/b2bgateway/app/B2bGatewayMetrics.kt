package io.eia.b2bgateway.app

import io.eia.b2bgateway.application.port.inbound.PollSummary
import io.eia.b2bgateway.application.port.inbound.RejectionStage
import io.eia.b2bgateway.application.port.inbound.SendSummary
import io.eia.b2bgateway.application.port.inbound.SubmitSummary
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import java.util.concurrent.ConcurrentHashMap

/**
 * b2b-gateway の運用のメトリクス(期限のメトリクスは adapters の `OrderSubmissionMetrics`、鍵の期限は platform/trading-partner)。
 *
 * | メトリクス | 種類 | 属性 |
 * |---|---|---|
 * | `eia.b2b.documents` | counter | `trading_partner_id`・`b2b.outcome`(`unreadable`・`no_envelope`・`rejected`・`checked`・`resent`)・
 *   `b2b.stage`(拒否した段階 `decryption`・`syntax`・`schema`・`business`。拒否がなければ `none`) |
 * | `eia.b2b.documents.rejected` | counter | `trading_partner_id`・`b2b.stage`。拒否した文書の数(運用通知のアラートの元) |
 * | `eia.b2b.inbox.failures` | counter | `trading_partner_id`・`b2b.reason`(`list`: 受け取りのディレクトリを見られない / `document`: 文書の受け付けの失敗) |
 * | `eia.b2b.inbox.stuck` | gauge | `trading_partner_id`。直近の見回りで、受け付けに失敗して処理中のディレクトリに残ったファイルの数 |
 * | `eia.b2b.acknowledgements` | counter | `trading_partner_id`・`b2b.outcome`(`sent`・`failed`)。応答(CONTRL・APERAK)の送信 |
 * | `eia.b2b.order_submissions` | counter | `b2b.outcome`(`accepted`・`refused`・`expired`・`failed`)。社内への取り込み |
 *
 * 属性にファイルの名前・注文番号は入れない(系列が増え続けるため)。
 *
 * アラートの元のカウンタ(拒否・失敗)は、起動時に台帳の取引先([partners])ごとに 0 で作る。Prometheus の `increase` は、系列が 1 で
 * 生まれたときの最初の 1 件を増加として数えないため(取引先の最初の拒否を知らせられなくなる)。
 */
internal class B2bGatewayMetrics(
    meter: Meter,
    partners: EdiPartners = EdiPartners(emptyList(), emptyList()),
) {
    private val documents: LongCounter =
        meter
            .counterBuilder("eia.b2b.documents")
            .setUnit("{document}")
            .setDescription("受け取った文書の数")
            .build()
    private val inboxFailures: LongCounter =
        meter
            .counterBuilder("eia.b2b.inbox.failures")
            .setUnit("{failure}")
            .setDescription("受け取りの失敗の数")
            .build()
    private val acknowledgements: LongCounter =
        meter
            .counterBuilder("eia.b2b.acknowledgements")
            .setUnit("{acknowledgement}")
            .setDescription("応答(CONTRL・APERAK)の送信の数")
            .build()
    private val submissions: LongCounter =
        meter
            .counterBuilder("eia.b2b.order_submissions")
            .setUnit("{submission}")
            .setDescription("社内への取り込みの結果の数")
            .build()
    private val rejected: LongCounter =
        meter
            .counterBuilder("eia.b2b.documents.rejected")
            .setUnit("{document}")
            .setDescription("拒否した文書の数(段階ごと)")
            .build()
    private val stuck = ConcurrentHashMap<String, Long>()

    init {
        partners.inbound.forEach { partner ->
            REJECTION_STAGES.forEach { rejected.add(0, Attributes.of(PARTNER, partner, STAGE, it)) }
            listOf("list", "document").forEach { inboxFailures.add(0, Attributes.of(PARTNER, partner, REASON, it)) }
        }
        partners.acknowledgements.forEach { partner -> acknowledgements.add(0, Attributes.of(PARTNER, partner, OUTCOME, "failed")) }
        submissions.add(0, Attributes.of(OUTCOME, "failed"))
        meter
            .gaugeBuilder("eia.b2b.inbox.stuck")
            .ofLongs()
            .setUnit("{file}")
            .setDescription("直近の見回りで、受け付けに失敗して処理中に残ったファイルの数")
            .buildWithCallback { measurement ->
                stuck.forEach { (partner, count) -> measurement.record(count, Attributes.of(PARTNER, partner)) }
            }
    }

    fun polled(
        partnerId: String,
        summary: PollSummary,
    ) {
        summary.accepted.forEach {
            val stage = it.stage.name.lowercase()
            documents.add(1, Attributes.of(PARTNER, partnerId, OUTCOME, it.outcome.name.lowercase(), STAGE, stage))
            if (it.stage != RejectionStage.NONE) rejected.add(1, Attributes.of(PARTNER, partnerId, STAGE, stage))
        }
        if (summary.failed.isNotEmpty()) {
            inboxFailures.add(
                summary.failed.size.toLong(),
                Attributes.of(PARTNER, partnerId, REASON, "document"),
            )
        }
        stuck[partnerId] = summary.failed.size.toLong()
    }

    fun pollFailed(partnerId: String) {
        inboxFailures.add(1, Attributes.of(PARTNER, partnerId, REASON, "list"))
    }

    fun sent(
        partnerId: String,
        summary: SendSummary,
    ) {
        if (summary.sent.isNotEmpty()) acknowledgements.add(summary.sent.size.toLong(), Attributes.of(PARTNER, partnerId, OUTCOME, "sent"))
        if (summary.failed.isNotEmpty()) {
            acknowledgements.add(
                summary.failed.size.toLong(),
                Attributes.of(PARTNER, partnerId, OUTCOME, "failed"),
            )
        }
    }

    fun submitted(summary: SubmitSummary) {
        listOf(
            "accepted" to summary.accepted.size,
            "refused" to summary.refused.size,
            "expired" to summary.expired.size,
            "failed" to summary.failed.size,
        ).filter { it.second > 0 }
            .forEach { (outcome, count) -> submissions.add(count.toLong(), Attributes.of(OUTCOME, outcome)) }
    }

    private companion object {
        val PARTNER: AttributeKey<String> = AttributeKey.stringKey("trading_partner_id")
        val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("b2b.outcome")
        val REASON: AttributeKey<String> = AttributeKey.stringKey("b2b.reason")
        val STAGE: AttributeKey<String> = AttributeKey.stringKey("b2b.stage")
        val REJECTION_STAGES = RejectionStage.entries.filter { it != RejectionStage.NONE }.map { it.name.lowercase() }
    }
}
