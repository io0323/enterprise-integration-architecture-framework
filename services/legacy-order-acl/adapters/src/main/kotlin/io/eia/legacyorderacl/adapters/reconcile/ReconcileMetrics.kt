package io.eia.legacyorderacl.adapters.reconcile

import io.eia.legacyorderacl.application.port.inbound.Mismatch
import io.eia.legacyorderacl.application.port.inbound.ReconciliationReport
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * 照合のメトリクス(ADR-0027。監査のアンカーと同じ形: 「最後に成功した時刻」で監視し、変化がない時間帯に誤報を出さない)。
 *
 * | 名前 | 種類 | 内容 |
 * |---|---|---|
 * | `eia.reconcile.last_success` | gauge(秒。UNIX 時刻) | 最後に照合が成功した(比べ終えた)時刻。ずれがあっても、比べ終えれば成功。**監視の対象** |
 * | `eia.reconcile.interval` | gauge(秒) | 照合の間隔(アラートの条件に使う) |
 * | `eia.reconcile.drift_keys` | gauge | 直近の照合で、比べ直しても食い違ったキーの数。`kind`(`missing`・`stale`・`extra`) |
 * | `eia.reconcile.unconvertible_keys` | gauge | 直近の照合で、変換できない(DLQ に入る)キーの数。既知の差 |
 * | `eia.reconcile.checks` | counter | 照合の回数。`outcome`(`consistent`・`drift`・`error`。`error` は `error.code` つき) |
 * | `eia.reconcile.resynced_keys` | counter | 再同期したキーの数。`action`(`snapshot`・`tombstone`) |
 * | `eia.reconcile.resyncs` | counter | 再同期の判断の回数。`outcome`(`requested`・`over_limit`・`disabled`・`error`) |
 *
 * 起動の後、最初の照合が終わるまで `last_success` は起動の時刻にする(起動の直後に Stale を出さない)。
 */
public class ReconcileMetrics(
    meter: Meter,
    interval: Duration,
    private val clock: Clock = Clock.System,
) {
    private val lastSuccess = AtomicReference(clock.now())
    private val latest = AtomicReference<ReconciliationReport?>(null)
    private val checks: LongCounter =
        meter
            .counterBuilder("eia.reconcile.checks")
            .setDescription("照合の回数(結果ごと)")
            .setUnit("{check}")
            .build()
    private val resynced: LongCounter =
        meter
            .counterBuilder("eia.reconcile.resynced_keys")
            .setDescription("再同期したキーの数(方法ごと)")
            .setUnit("{key}")
            .build()
    private val resyncs: LongCounter =
        meter
            .counterBuilder("eia.reconcile.resyncs")
            .setDescription("再同期の判断の回数(結果ごと)")
            .setUnit("{resync}")
            .build()

    init {
        meter
            .gaugeBuilder("eia.reconcile.last_success")
            .setUnit("s")
            .setDescription("最後に照合が成功した時刻(UNIX 時刻)")
            .buildWithCallback { it.record(lastSuccess.get().epochSeconds.toDouble()) }
        meter
            .gaugeBuilder("eia.reconcile.interval")
            .setUnit("s")
            .setDescription("照合の間隔")
            .buildWithCallback { it.record(interval.inWholeSeconds.toDouble()) }
        meter
            .gaugeBuilder("eia.reconcile.drift_keys")
            .ofLongs()
            .setUnit("{key}")
            .setDescription("直近の照合で、比べ直しても食い違ったキーの数")
            .buildWithCallback { measurement ->
                latest.get()?.let { report ->
                    Mismatch.entries.forEach { kind ->
                        measurement.record(
                            report.drift.values
                                .count { it == kind }
                                .toLong(),
                            Attributes.of(KIND, kind.name.lowercase()),
                        )
                    }
                }
            }
        meter
            .gaugeBuilder("eia.reconcile.unconvertible_keys")
            .ofLongs()
            .setUnit("{key}")
            .setDescription("直近の照合で、変換できない(DLQ に入る)キーの数")
            .buildWithCallback { measurement -> latest.get()?.let { measurement.record(it.unconvertible.size.toLong()) } }
    }

    public fun succeeded(report: ReconciliationReport) {
        latest.set(report)
        lastSuccess.set(clock.now())
        checks.add(1, Attributes.of(OUTCOME, if (report.consistent) "consistent" else "drift"))
    }

    /** 再同期の判断の結果([outcome])と、取り直したキーの数。 */
    public fun resynced(
        outcome: String,
        snapshot: Int = 0,
        tombstone: Int = 0,
    ) {
        resyncs.add(1, Attributes.of(OUTCOME, outcome))
        if (snapshot > 0) resynced.add(snapshot.toLong(), Attributes.of(ACTION, "snapshot"))
        if (tombstone > 0) resynced.add(tombstone.toLong(), Attributes.of(ACTION, "tombstone"))
    }

    public fun failed(errorCode: String) {
        checks.add(1, Attributes.of(OUTCOME, "error", ERROR_CODE, errorCode))
    }

    /** テストと Runbook の確認用。 */
    public val lastSucceededAt: Instant get() = lastSuccess.get()

    private companion object {
        val KIND: AttributeKey<String> = AttributeKey.stringKey("kind")
        val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
        val ACTION: AttributeKey<String> = AttributeKey.stringKey("action")
        val ERROR_CODE: AttributeKey<String> = AttributeKey.stringKey("error.code")
    }
}
