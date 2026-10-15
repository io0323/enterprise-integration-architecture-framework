package io.eia.platform.filetransfer

import io.eia.platform.filetransfer.layout.ColumnType
import io.eia.platform.filetransfer.layout.FileColumn
import io.eia.platform.filetransfer.layout.FileFormat
import io.eia.platform.filetransfer.layout.FileLayout
import io.eia.platform.filetransfer.layout.FileName
import io.eia.platform.filetransfer.write.PublishRequest
import java.time.LocalDate
import kotlin.time.Instant

/** テストの共通のデータ。列は contracts/files/sales_daily.v1.yaml にならう(全部の型を含めるため、件数と真偽の列を足す)。 */
internal object Fixtures {
    const val INBOX = "inbox/"
    const val ARCHIVE = "archive/"
    const val QUARANTINE = "quarantine/"
    const val TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    val CREATED_AT: Instant = Instant.parse("2026-10-14T16:00:00Z")

    val COLUMNS =
        listOf(
            FileColumn("business_date", ColumnType.DATE, nullable = false),
            FileColumn("order_id", ColumnType.STRING, nullable = false),
            FileColumn("ordered_at", ColumnType.TIMESTAMP, nullable = false),
            FileColumn("status", ColumnType.STRING, nullable = false, enumValues = setOf("PLACED", "CANCELLED")),
            FileColumn("total_minor_units", ColumnType.INT64, nullable = false),
            FileColumn("lines", ColumnType.INT32, nullable = false),
            FileColumn("gift", ColumnType.BOOLEAN, nullable = false),
            FileColumn("ship_region", ColumnType.STRING, nullable = true),
        )

    val LAYOUT: FileLayout = FileLayout.of("sales", "daily", 1, COLUMNS).ok()

    fun row(
        index: Int,
        region: String? = "JP-13",
    ): List<Any?> =
        listOf(
            LocalDate.of(2026, 10, 14),
            "order-%04d".format(index),
            java.time.Instant
                .parse("2026-10-14T01:02:03.123456Z")
                .plusSeconds(index.toLong()),
            if (index % 5 == 0) "CANCELLED" else "PLACED",
            1_000L * index,
            index % 3 + 1,
            index % 2 == 0,
            region,
        )

    fun rows(count: Int): Sequence<List<Any?>> = (1..count).asSequence().map { row(it) }

    fun name(
        format: FileFormat,
        seq: Int = 1,
        layout: FileLayout = LAYOUT,
    ): FileName = FileName.of(layout, CREATED_AT, seq, format).ok()

    fun request(
        format: FileFormat,
        seq: Int = 1,
        layout: FileLayout = LAYOUT,
        traceparent: String = TRACEPARENT,
    ): PublishRequest = PublishRequest(INBOX, layout, name(format, seq, layout), traceparent, "corr-1", CREATED_AT)
}
