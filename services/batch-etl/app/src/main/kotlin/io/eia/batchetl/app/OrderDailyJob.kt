package io.eia.batchetl.app

import io.eia.batchetl.adapters.kafka.IngestLag
import io.eia.batchetl.application.port.inbound.BuildDailyOrderMartUseCase
import io.eia.batchetl.application.port.inbound.IngestOrderEventsUseCase
import io.eia.batchetl.application.port.inbound.IngestReport
import io.eia.batchetl.application.port.inbound.MartBuildReport
import io.eia.batchetl.domain.BusinessDay
import io.eia.platform.batch.BatchJob
import io.eia.platform.batch.DagDefinition
import io.eia.platform.batch.Schedule
import io.eia.platform.batch.Trigger
import io.eia.platform.batch.dag
import io.eia.platform.batch.sla.Sla
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.onOk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

/**
 * 日次の DAG `batch-etl.order-daily`(ADR-0031 §4)。パーティションは業務の日(日本時間)。
 *
 * 1. `ingest`: 注文のイベントを、前回の位置から末尾まで取り込む(住所は地域にしてから)。
 * 2. `transform`: その日と、遅れて届いたデータで変わった過去の日(31 日前まで)のパーティションを作り直す。
 *
 * 毎日 [dailyAt](日本時間)に前日の分を起動し、翌日の [slaAt](日本時間)までに終える(SLA。ADR-0030 §6)。
 */
internal object OrderDailyJob {
    const val DAG_ID: String = "batch-etl.order-daily"

    /** 連携カタログの ID(注文のイベント。INT-SALES-002)。 */
    const val INTEGRATION_ID: String = "INT-SALES-002"
    private val TOKYO = TimeZone.of(BusinessDay.TIME_ZONE_ID)

    fun dag(
        ingest: IngestOrderEventsUseCase,
        build: BuildDailyOrderMartUseCase,
        metrics: BatchEtlMetrics,
        slaAt: LocalTime,
    ): DagDefinition =
        dag(DAG_ID) {
            integrationId = INTEGRATION_ID
            sla = Sla.dailyAt(slaAt, TOKYO)
            val ingestStep =
                step("ingest") {
                    expected = 5.minutes
                    action { ingest().onOk(metrics::ingested).map { } }
                }
            step("transform", ingestStep) {
                expected = 5.minutes
                action { context ->
                    BusinessDay
                        .parse(context.run.partition.value, "partition")
                        .flatMap { day -> build(day).onOk { metrics.built(day, it) } }
                        .map { }
                }
            }
        }

    fun job(
        dag: DagDefinition,
        dailyAt: LocalTime,
    ): BatchJob = BatchJob(dag, Trigger.Scheduled(Schedule.daily(dailyAt, TOKYO)))
}

/**
 * batch-etl のメトリクス(バッチの共通のメトリクスは platform/batch の `BatchMetrics`)。
 *
 * | メトリクス | 種類 | 属性 |
 * |---|---|---|
 * | `eia.batch_etl.events.ingested` | counter | なし(取り込んだイベント。重複を含む) |
 * | `eia.batch_etl.events.dead_lettered` | counter | なし(読めずに DLQ に送ったイベント) |
 * | `eia.batch_etl.partitions.rebuilt` | counter | `batch_etl.late`(実行の日より前の日を作り直したら true) |
 * | `eia.batch_etl.late_days.pending` | gauge | なし。自動では作り直さない古い日(31 日より前)で、印が残っている日の数(ADR-0031 §5。0 より大きければ運用者が判断する) |
 * | `eia.batch_etl.ingest.lag_age` | gauge(秒) | なし。まだ取り込んでいないイベントのうち最も古いものの経過時間(ADR-0031 §7) |
 * | `eia.batch_etl.ingest.retention` | gauge(秒) | なし。注文のイベントのトピックの保持期間(アラートの閾値はこれに対する割合) |
 * | `eia.batch_etl.ingest.lost_events` | gauge | なし。保持期間を過ぎて消え、取り込めなくなったイベントの件数(0 より大きければ回復の手順) |
 */
internal class BatchEtlMetrics(
    meter: Meter,
) {
    private val ingested: LongCounter =
        meter
            .counterBuilder("eia.batch_etl.events.ingested")
            .setUnit("{event}")
            .setDescription("取り込んだ注文のイベントの件数")
            .build()
    private val deadLettered: LongCounter =
        meter
            .counterBuilder("eia.batch_etl.events.dead_lettered")
            .setUnit("{event}")
            .setDescription("読めずに DLQ に送ったイベントの件数")
            .build()
    private val rebuilt: LongCounter =
        meter
            .counterBuilder("eia.batch_etl.partitions.rebuilt")
            .setUnit("{partition}")
            .setDescription("作り直した日次のパーティションの数")
            .build()
    private val pending = AtomicLong()
    private val lag = AtomicReference<IngestLag?>()

    init {
        meter
            .gaugeBuilder("eia.batch_etl.late_days.pending")
            .ofLongs()
            .setUnit("{day}")
            .setDescription("遅れて届いたデータで変わったが、古いので自動では作り直さない日の数")
            .buildWithCallback { it.record(pending.get()) }
        meter
            .gaugeBuilder("eia.batch_etl.ingest.lag_age")
            .setUnit("s")
            .setDescription("まだ取り込んでいないイベントのうち最も古いものの経過時間")
            .buildWithCallback { measurement -> lag.get()?.let { measurement.record(it.oldestPendingAge.toDouble(DurationUnit.SECONDS)) } }
        meter
            .gaugeBuilder("eia.batch_etl.ingest.retention")
            .setUnit("s")
            .setDescription("注文のイベントのトピックの保持期間")
            .buildWithCallback { measurement -> lag.get()?.retention?.let { measurement.record(it.toDouble(DurationUnit.SECONDS)) } }
        meter
            .gaugeBuilder("eia.batch_etl.ingest.lost_events")
            .ofLongs()
            .setUnit("{event}")
            .setDescription("保持期間を過ぎて消え、取り込めなくなったイベントの件数")
            .buildWithCallback { measurement -> lag.get()?.let { measurement.record(it.lost.values.sum()) } }
    }

    /** 取り込みの遅れを確かめた結果(serve が定期的に確かめる)。 */
    fun lag(value: IngestLag) {
        lag.set(value)
    }

    fun ingested(report: IngestReport) {
        ingested.add(report.loaded.toLong())
        deadLettered.add(report.deadLettered.toLong())
    }

    fun built(
        runDay: BusinessDay,
        report: MartBuildReport,
    ) {
        report.rebuilt.forEach { (day, _) -> rebuilt.add(1, Attributes.of(LATE, day != runDay)) }
        pending.set(report.tooOld.size.toLong())
    }

    private companion object {
        val LATE: AttributeKey<Boolean> = AttributeKey.booleanKey("batch_etl.late")
    }
}
