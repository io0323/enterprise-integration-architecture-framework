package io.eia.platform.filetransfer.write

import io.eia.platform.filetransfer.layout.ColumnType
import io.eia.platform.filetransfer.layout.FileLayout
import java.time.Instant
import java.time.LocalDate

/** 行の値が契約の列(型・nullable・取りうる値)に合うか。理由には列の名前と型だけを入れ、値は入れない(個人情報を含みうる)。 */
internal object RowCheck {
    fun problem(
        layout: FileLayout,
        row: List<Any?>,
    ): String? =
        if (row.size != layout.columns.size) {
            "列の数が ${row.size} です(契約は ${layout.columns.size})"
        } else {
            layout.columns.zip(row).firstNotNullOfOrNull { (column, value) ->
                when {
                    value == null -> if (column.nullable) null else "${column.name} は null にできません"
                    !matches(column.type, value) -> "${column.name} の値は ${column.type.valueType} にしてください"
                    column.enumValues != null && value !in column.enumValues -> "${column.name} の値が取りうる値にありません"
                    else -> null
                }
            }
        }

    private fun matches(
        type: ColumnType,
        value: Any,
    ): Boolean =
        when (type) {
            ColumnType.STRING -> value is String
            ColumnType.INT32 -> value is Int
            ColumnType.INT64 -> value is Long
            ColumnType.BOOLEAN -> value is Boolean
            ColumnType.DATE -> value is LocalDate
            ColumnType.TIMESTAMP -> value is Instant
        }
}
