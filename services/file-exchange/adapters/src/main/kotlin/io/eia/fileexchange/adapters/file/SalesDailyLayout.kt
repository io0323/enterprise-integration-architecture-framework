package io.eia.fileexchange.adapters.file

import io.eia.platform.filetransfer.layout.ColumnType
import io.eia.platform.filetransfer.layout.FileColumn
import io.eia.platform.filetransfer.layout.FileLayout
import io.eia.shared.kernel.Result

/**
 * 受け取る売上ファイル(`contracts/files/sales_daily.v1.yaml`。INT-SALES-004)の形。検査([io.eia.platform.filetransfer.verify.FileVerifier])
 * が、列・型・nullable・件数をこれと照らす。送り手(batch-etl)も同じ契約から同じ形を持つ(サービスどうしは依存しない)。
 * 契約と一致することは単体テスト(`SalesDailyLayoutSpec`)で確かめる(ADR-0032 §2)。
 */
public object SalesDailyLayout {
    private val STATUSES = setOf("PLACED", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED")

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
                        FileColumn("status", ColumnType.STRING, nullable = false, enumValues = STATUSES),
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
}
