package io.eia.bffgraphql.adapters.outbound

import io.ktor.client.statement.HttpResponse
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import java.util.concurrent.atomic.AtomicLong

/**
 * Gateway の BFF の枠の使用状況(ADR-0043 §9)。アラート `BffOrderQuotaHigh` が、1 分の呼び出しの数を枠で割って使用率にする。
 *
 * 枠の値を BFF の設定に複製しないよう、Gateway の応答の `X-RateLimit-Limit` から読む。Gateway の設定を変えれば、アラートの分母も変わる。
 * 枠を見ていない間(Gateway に届いていない)は、枠のメトリクスを出さない。
 *
 * | メトリクス | 内容 |
 * |---|---|
 * | `eia.bff.order_quota.calls` | Gateway に届いた order の呼び出しの数(リトライの 1 回ごと。429 も含む) |
 * | `eia.bff.order_quota.limit` | Gateway が知らせた枠(最後に見た値) |
 */
public class OrderQuotaMetrics(
    meter: Meter,
) {
    private val limit = AtomicLong(UNKNOWN)
    private val calls: LongCounter =
        meter
            .counterBuilder("eia.bff.order_quota.calls")
            .setUnit("{call}")
            .setDescription("Gateway に届いた order の呼び出しの数(BFF の枠を消費する)")
            .build()

    init {
        meter
            .gaugeBuilder("eia.bff.order_quota.limit")
            .ofLongs()
            .setUnit("{call}")
            .setDescription("Gateway が知らせた BFF の枠(時間窓あたりの呼び出しの数)")
            .buildWithCallback { measurement -> limit.get().takeIf { it != UNKNOWN }?.let(measurement::record) }
    }

    internal fun observe(response: HttpResponse) {
        val reported = response.headers[LIMIT_HEADER]?.toLongOrNull() ?: return
        limit.set(reported)
        calls.add(1)
    }

    private companion object {
        const val LIMIT_HEADER = "X-RateLimit-Limit"
        const val UNKNOWN = -1L
    }
}
