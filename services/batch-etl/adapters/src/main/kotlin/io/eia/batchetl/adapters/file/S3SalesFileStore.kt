package io.eia.batchetl.adapters.file

import io.eia.batchetl.application.port.outbound.ExportedFileInfo
import io.eia.batchetl.application.port.outbound.SalesFileFormat
import io.eia.batchetl.application.port.outbound.SalesFileStore
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.OrderStatus
import io.eia.batchetl.domain.SalesFileLine
import io.eia.batchetl.domain.SalesFileRevision
import io.eia.platform.filetransfer.layout.ColumnType
import io.eia.platform.filetransfer.layout.FileColumn
import io.eia.platform.filetransfer.layout.FileFormat
import io.eia.platform.filetransfer.layout.FileLayout
import io.eia.platform.filetransfer.layout.FileName
import io.eia.platform.filetransfer.write.FilePublisher
import io.eia.platform.filetransfer.write.PublishRequest
import io.eia.platform.observability.context.CurrentTrace
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlin.time.Clock
import kotlin.time.toJavaInstant

/**
 * 売上ファイル(INT-SALES-004)を S3 互換ストレージに置く(platform/file-transfer の [FilePublisher]。ADR-0032 §2)。
 *
 * - 名前は `sales_daily_{業務の日の締め(UTC)}_{版 3 桁}.{ext}`([SalesFileRevision])。同じ版は同じ名前で上書きする
 * - マニフェストの traceparent はこの書き出しの span(DAG のステップの span)。受け手の実行はこれを親にする(ADR-0030 §5)
 *
 * @property prefix 置き場のキーの接頭辞(例 `inbox/sales-daily/`)
 */
public class S3SalesFileStore(
    private val publisher: FilePublisher,
    private val prefix: String,
    private val clock: Clock = Clock.System,
) : SalesFileStore {
    @Suppress("ReturnCount") // traceparent がない・名前の誤り・置き場の失敗のそれぞれで返す
    override suspend fun publish(
        day: BusinessDay,
        revision: Int,
        format: SalesFileFormat,
        lines: Sequence<SalesFileLine>,
    ): Result<ExportedFileInfo, DomainError> {
        val trace = CurrentTrace.get()
        val traceparent = trace.traceParent?.format() ?: return err(UnexpectedError("書き出しの span の traceparent がありません"))
        val correlationId = (trace.correlationId ?: CorrelationId.generate()).value
        val fileFormat = fileFormat(format)
        val name =
            when (val made = FileName.of(SalesDailyLayout.V1, SalesFileRevision.cutoff(day), revision, fileFormat)) {
                is Result.Err -> return err(made.error.asDomainError())
                is Result.Ok -> made.value
            }
        val request = PublishRequest(prefix, SalesDailyLayout.V1, name, traceparent, correlationId, clock.now())
        return when (val published = withContext(Dispatchers.IO) { publisher.publish(request, lines.map(SalesDailyLayout::row)) }) {
            is Result.Err -> err(published.error.asDomainError())
            is Result.Ok -> ok(ExportedFileInfo(name.dataFile, published.value.manifest.recordCount))
        }
    }

    private fun fileFormat(format: SalesFileFormat): FileFormat =
        when (format) {
            SalesFileFormat.PARQUET -> FileFormat.PARQUET
            SalesFileFormat.CSV_GZIP -> FileFormat.CSV_GZIP
        }
}

/**
 * `contracts/files/sales_daily.v1.yaml` をコードにしたもの(列の名前・型・nullable・取りうる値・CSV の書き方)。
 * 契約と一致することは単体テスト(`SalesDailyLayoutSpec`)で確かめる(ADR-0032 §2)。
 */
public object SalesDailyLayout {
    public val V1: FileLayout =
        when (
            val layout =
                FileLayout.of(
                    "sales",
                    "daily",
                    1,
                    listOf(
                        FileColumn("business_date", ColumnType.DATE, nullable = false),
                        FileColumn("order_id", ColumnType.STRING, nullable = false),
                        FileColumn("ordered_at", ColumnType.TIMESTAMP, nullable = false),
                        FileColumn("status", ColumnType.STRING, nullable = false, enumValues = OrderStatus.entries.map { it.name }.toSet()),
                        FileColumn("total_minor_units", ColumnType.INT64, nullable = false),
                        FileColumn("currency", ColumnType.STRING, nullable = false),
                        FileColumn("ship_country", ColumnType.STRING, nullable = false),
                        FileColumn("ship_region", ColumnType.STRING, nullable = true),
                    ),
                )
        ) {
            is Result.Ok -> layout.value
            is Result.Err -> error("売上ファイルの形が誤っています: ${layout.error.message}")
        }

    /** 1 行を、列の順の値(file-transfer の行の型)にする。 */
    internal fun row(line: SalesFileLine): List<Any?> =
        listOf(
            line.businessDate.toJavaLocalDate(),
            line.orderId,
            line.orderedAt.toJavaInstant(),
            line.status.name,
            line.totalMinorUnits,
            line.currency,
            line.shipCountry,
            line.shipRegion,
        )
}
