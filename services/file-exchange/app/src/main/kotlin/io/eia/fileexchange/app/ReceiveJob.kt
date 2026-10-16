package io.eia.fileexchange.app

import io.eia.fileexchange.application.port.inbound.DeliverFileUseCase
import io.eia.fileexchange.application.port.inbound.DeliveryReport
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
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import io.eia.shared.kernel.onOk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

/**
 * 受け取りの DAG `file-exchange.receive`(ADR-0032 §5)。受け口に `.manifest.json` が届いたら、ファイルの名前をパーティションにして起動する。
 * 実行の span の親はマニフェストの traceparent(送り手の書き出しの span。ADR-0030 §5)。
 * データファイルがなくても起動し(`requireDataFile = false`)、欠損は検査で不合格にして quarantine に送る。
 *
 * 受け取りの後に、合格のファイルを取引先に配送する(`deliver`。ADR-0032 §8)。1 つの取引先に送れなければ、ほかの取引先に送ってから
 * ステップを失敗にする(ステップのリトライと、運用者の再実行 `receive <名前>` で、送れなかった取引先だけに送る)。
 */
internal object ReceiveJob {
    val DAG_ID: DagId = DagId("file-exchange.receive")

    /** 連携カタログの ID(日次の売上ファイル)。 */
    const val INTEGRATION_ID: String = "INT-SALES-004"

    private val logger = LoggerFactory.getLogger(ReceiveJob::class.java)

    fun dag(
        receive: ReceiveFileUseCase,
        deliver: DeliverFileUseCase,
        metrics: FileExchangeMetrics,
    ): DagDefinition =
        dag(DAG_ID.value) {
            integrationId = INTEGRATION_ID
            val received =
                step("receive") {
                    expected = 1.minutes
                    action { context -> receive(context.run.partition.value).onOk(metrics::received).map { } }
                }
            step("deliver", received) {
                expected = 5.minutes
                action { context ->
                    deliver(context.run.partition.value).flatMap { report ->
                        metrics.delivered(report)
                        report.failures.forEach { logger.warn("{} を取引先 {} に送れません({})", report.name, it.partner, it.error.code) }
                        report.failures.firstOrNull()?.let { err(it.error) } ?: ok(Unit)
                    }
                }
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
 * | `eia.file_exchange.deliveries` | counter | `file_exchange.partner`(取引先の ID)・`file_exchange.outcome`(下記) |
 *
 * 配送の結果は `sent`(送った)・`already_present`(相手の側にあった)・`already_sent`(送信済み)・`failed`(送れなかった)。
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
    private val deliveries: LongCounter =
        meter
            .counterBuilder("eia.file_exchange.deliveries")
            .setUnit("{delivery}")
            .setDescription("取引先への配送の数(取引先と結果ごと)")
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

    fun delivered(report: DeliveryReport) {
        report.delivered.forEach { deliveries.add(1, Attributes.of(PARTNER, it.partner.value, OUTCOME, it.outcome.code)) }
        report.failures.forEach { deliveries.add(1, Attributes.of(PARTNER, it.partner.value, OUTCOME, "failed")) }
    }

    fun swept(report: SweepReport) {
        report.moved.forEach { extra.add(1, Attributes.of(REASON, it.reason.code)) }
        lastSweep.set(report)
    }

    private companion object {
        val DESTINATION: AttributeKey<String> = AttributeKey.stringKey("file_exchange.destination")
        val REASON: AttributeKey<String> = AttributeKey.stringKey("file_exchange.reason")
        val PARTNER: AttributeKey<String> = AttributeKey.stringKey("file_exchange.partner")
        val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("file_exchange.outcome")
    }
}
