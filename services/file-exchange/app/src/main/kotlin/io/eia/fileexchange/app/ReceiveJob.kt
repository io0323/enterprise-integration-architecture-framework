package io.eia.fileexchange.app

import io.eia.fileexchange.application.port.inbound.ReceiveFileUseCase
import io.eia.fileexchange.application.port.inbound.ReceiveOutcome
import io.eia.fileexchange.application.port.inbound.SweepReport
import io.eia.fileexchange.domain.ReceiptDestination
import io.eia.platform.batch.BatchJob
import io.eia.platform.batch.DagDefinition
import io.eia.platform.batch.DagId
import io.eia.platform.batch.Trigger
import io.eia.platform.batch.dag
import io.eia.platform.filetransfer.store.ObjectReader
import io.eia.shared.kernel.map
import io.eia.shared.kernel.onOk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

/**
 * 受け取りの DAG `file-exchange.receive`(ADR-0032 §5)。受け口に `.manifest.json` が届いたら、ファイルの名前をパーティションにして起動する。
 * 実行の span の親はマニフェストの traceparent(送り手の書き出しの span。ADR-0030 §5)。
 * データファイルがなくても起動し(`requireDataFile = false`)、欠損は検査で不合格にして quarantine に送る。
 */
internal object ReceiveJob {
    val DAG_ID: DagId = DagId("file-exchange.receive")

    /** 連携カタログの ID(日次の売上ファイル)。 */
    const val INTEGRATION_ID: String = "INT-SALES-004"

    fun dag(
        receive: ReceiveFileUseCase,
        metrics: FileExchangeMetrics,
    ): DagDefinition =
        dag(DAG_ID.value) {
            integrationId = INTEGRATION_ID
            step("receive") {
                expected = 1.minutes
                action { context -> receive(context.run.partition.value).onOk(metrics::received).map { } }
            }
        }

    fun job(
        dag: DagDefinition,
        inbox: ObjectReader,
        inboxPrefix: String,
    ): BatchJob = BatchJob(dag, Trigger.FileArrival(inbox, inboxPrefix, requireDataFile = false))
}

/**
 * file-exchange のメトリクス(実行の共通のメトリクスは platform/batch の `BatchMetrics`)。
 *
 * | メトリクス | 種類 | 属性 |
 * |---|---|---|
 * | `eia.file_exchange.files.received` | counter | `file_exchange.destination`(`archive`・`quarantine`)・`file_exchange.reason`(合格は `none`) |
 * | `eia.file_exchange.files.extra` | counter | `file_exchange.reason`(`duplicate` / `name_reused`)。受け取り済みの名前で届いたファイル |
 * | `eia.file_exchange.inbox.waiting` | gauge | なし。直近の見回りで、受け口に残っていたファイルの数 |
 * | `eia.file_exchange.inbox.oldest_age` | gauge(秒) | なし。受け口に残っているファイルのうち、マニフェストの createdAt が最も古いものの経過時間 |
 *
 * 属性にファイルの名前は入れない(系列が増え続けるため)。
 */
internal class FileExchangeMetrics(
    meter: Meter,
    private val clock: Clock = Clock.System,
) {
    private val received: LongCounter =
        meter
            .counterBuilder("eia.file_exchange.files.received")
            .setUnit("{file}")
            .setDescription("受け取ったファイルの数(移動先と理由ごと)")
            .build()
    private val extra: LongCounter =
        meter
            .counterBuilder("eia.file_exchange.files.extra")
            .setUnit("{file}")
            .setDescription("受け取り済みの名前で届き、quarantine に移したファイルの数")
            .build()
    private val lastSweep = AtomicReference<SweepReport?>()

    init {
        meter
            .gaugeBuilder("eia.file_exchange.inbox.waiting")
            .ofLongs()
            .setUnit("{file}")
            .setDescription("受け口に残っていたファイルの数")
            .buildWithCallback { measurement -> lastSweep.get()?.let { measurement.record(it.waiting.toLong()) } }
        meter
            .gaugeBuilder("eia.file_exchange.inbox.oldest_age")
            .setUnit("s")
            .setDescription("受け口に残っているファイルのうち、最も古いものの経過時間")
            .buildWithCallback { measurement ->
                val oldest = lastSweep.get()?.oldestCreatedAt
                measurement.record(if (oldest == null) 0.0 else (clock.now() - oldest).toDouble(DurationUnit.SECONDS).coerceAtLeast(0.0))
            }
    }

    fun received(outcome: ReceiveOutcome) {
        if (outcome.alreadyStored) return
        val destination = if (outcome.file.destination == ReceiptDestination.ARCHIVE) "archive" else "quarantine"
        received.add(1, Attributes.of(DESTINATION, destination, REASON, outcome.file.reason ?: "none"))
    }

    fun swept(report: SweepReport) {
        report.moved.forEach { extra.add(1, Attributes.of(REASON, it.reason.code)) }
        lastSweep.set(report)
    }

    private companion object {
        val DESTINATION: AttributeKey<String> = AttributeKey.stringKey("file_exchange.destination")
        val REASON: AttributeKey<String> = AttributeKey.stringKey("file_exchange.reason")
    }
}
