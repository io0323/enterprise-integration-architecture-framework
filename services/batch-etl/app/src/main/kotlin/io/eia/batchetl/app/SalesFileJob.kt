package io.eia.batchetl.app

import io.eia.batchetl.application.port.inbound.ExportDailySalesFileUseCase
import io.eia.batchetl.domain.BusinessDay
import io.eia.platform.batch.BatchJob
import io.eia.platform.batch.DagDefinition
import io.eia.platform.batch.DagId
import io.eia.platform.batch.Trigger
import io.eia.platform.batch.dag
import io.eia.platform.batch.sla.Sla
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

/**
 * 日次の売上ファイルの DAG `batch-etl.sales-file`(INT-SALES-004。ADR-0031 §8)。パーティションは業務の日(日本時間)。
 *
 * 日次の DAG(`batch-etl.order-daily`)の同じ日が成功したら起動し(AfterDag)、その日の mart から売上ファイルを書いて置く。
 * 日次の DAG を作り直したら(RESTART)、こちらもやり直し、世代が増えるので新しい版(名前)のファイルを置く。
 * ファイルの版は実行の世代([io.eia.platform.batch.StepContext.generation])にする。同じ実行のやり直しは同じ名前で上書きする。
 */
internal object SalesFileJob {
    const val DAG_ID: String = "batch-etl.sales-file"

    /** 連携カタログの ID(日次の売上ファイル)。 */
    const val INTEGRATION_ID: String = "INT-SALES-004"
    private val TOKYO = TimeZone.of(BusinessDay.TIME_ZONE_ID)
    private val logger = LoggerFactory.getLogger(SalesFileJob::class.java)

    fun dag(
        export: ExportDailySalesFileUseCase,
        slaAt: LocalTime,
    ): DagDefinition =
        dag(DAG_ID) {
            integrationId = INTEGRATION_ID
            sla = Sla.dailyAt(slaAt, TOKYO)
            step("export") {
                expected = 2.minutes
                action { context ->
                    BusinessDay
                        .parse(context.run.partition.value, "partition")
                        .flatMap { day -> export(day, context.generation) }
                        .map { report -> report.files.forEach { logger.info("売上ファイル {} を置きました({} 件)", it.name, it.records) } }
                }
            }
        }

    fun job(dag: DagDefinition): BatchJob = BatchJob(dag, Trigger.AfterDag(DagId(OrderDailyJob.DAG_ID)))
}
