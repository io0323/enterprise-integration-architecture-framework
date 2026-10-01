package io.eia.platform.audit.anchor

import io.eia.platform.audit.AuditError
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicReference

/**
 * アンカーの定期的な保存([AnchorCycle])のメトリクス(ADR-0017 §8)。`platform/observability` の `ObservabilityRuntime.meter` を渡して作る。
 *
 * | メトリクス | 種類 | 内容 |
 * |---|---|---|
 * | `eia.audit.anchor.last_success` | gauge(秒。UNIX 時刻) | 最後に検査が成功した時刻(記録が増えていないことの確認、または保存の成功)。**監視の対象** |
 * | `eia.audit.anchor.last_published` | gauge(秒。UNIX 時刻) | 最後にアンカーを保存した時刻(起動の後の最初の保存までは、最新の版の作成時刻) |
 * | `eia.audit.anchor.interval` | gauge(秒) | 検査の間隔(アラートの条件に使う) |
 * | `eia.audit.anchor.checks` | counter | 検査の回数。`outcome`(`empty`・`unchanged`・`published`・`rejected`・`error`。`error` は `error.code` つき) |
 *
 * アラートの候補は「最後に検査が成功した時刻」で判定する(間隔の [STALE_AFTER_INTERVALS] 倍を超えたら。PromQL は ADR-0017 §8)。
 * 「最後にアンカーを保存した時刻」は、注文がない間は更新されない(記録が増えなければ保存しない)ため、アラートには使わない。
 * `last_success` の初期値は起動の時刻とする(起動してから一度も成功しない場合も、間隔の 2 倍で条件に当たる)。
 */
public class AnchorMetrics(
    meter: Meter,
    private val interval: Duration,
    clock: Clock = Clock.systemUTC(),
) : AnchorCycleListener {
    private val lastSuccess = AtomicReference(clock.instant())
    private val lastPublished = AtomicReference<Instant?>(null)
    private val checks: LongCounter =
        meter
            .counterBuilder("eia.audit.anchor.checks")
            .setUnit("{check}")
            .setDescription("アンカーの保存の前の検査の回数")
            .build()

    init {
        require(!interval.isNegative && !interval.isZero) { "interval は正の期間にしてください" }
        meter
            .gaugeBuilder("eia.audit.anchor.last_success")
            .setUnit("s")
            .setDescription("最後にアンカーの検査が成功した時刻(UNIX 時刻)")
            .buildWithCallback { it.record(lastSuccess.get().epochSeconds()) }
        meter
            .gaugeBuilder("eia.audit.anchor.last_published")
            .setUnit("s")
            .setDescription("最後にアンカーを保存した時刻(UNIX 時刻)")
            .buildWithCallback { measurement -> lastPublished.get()?.let { measurement.record(it.epochSeconds()) } }
        meter
            .gaugeBuilder("eia.audit.anchor.interval")
            .setUnit("s")
            .setDescription("アンカーの検査の間隔")
            .buildWithCallback { it.record(interval.toMillis() / MILLIS_PER_SECOND) }
    }

    override fun checked(
        outcome: AnchorOutcome,
        at: Instant,
    ) {
        checks.add(1, Attributes.of(OUTCOME, outcome.label))
        if (outcome.succeeded) lastSuccess.set(at)
        if (outcome is AnchorOutcome.Published) lastPublished.set(at)
    }

    override fun failed(
        error: AuditError,
        at: Instant,
    ) {
        checks.add(1, Attributes.of(OUTCOME, "error", ERROR_CODE, error.code))
    }

    override fun restored(anchor: Anchor) {
        val createdAt =
            try {
                Instant.parse(anchor.createdAt)
            } catch (_: DateTimeParseException) {
                return
            }
        lastPublished.compareAndSet(null, createdAt)
    }

    public companion object {
        /** アラートの候補: 最後に検査が成功してから、間隔のこの倍数を超えたら。 */
        public const val STALE_AFTER_INTERVALS: Long = 2
        private const val MILLIS_PER_SECOND = 1_000.0
        private val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
        private val ERROR_CODE: AttributeKey<String> = AttributeKey.stringKey("error.code")

        private fun Instant.epochSeconds(): Double = toEpochMilli() / MILLIS_PER_SECOND
    }
}
