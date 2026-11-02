package io.eia.b2bgateway.domain.codes

import io.eia.b2bgateway.domain.order.InternalCustomer
import io.eia.b2bgateway.domain.order.InternalItem
import io.eia.b2bgateway.domain.order.ItemCode
import io.eia.b2bgateway.domain.order.ShippingAddress

/**
 * 取引先ごとのコードの対応表の種類と、取り込むファイル(CSV)の列(ADR-0040 §6)。表は種類ごとに丸ごと入れ替える。
 *
 * | 種類 | 列 |
 * |---|---|
 * | `buyers` | `gln`・`customer_id`・既定の届け先(`country_code`・`postal_code`・`region`・`city`・`line1`・`line2`) |
 * | `delivery-parties` | `gln`・届け先(同上) |
 * | `items` | `code_type`(`gtin`・`buyer_part_number`)・`code`・`product_id`・`sku` |
 */
public enum class CodeKind(
    public val label: String,
    public val columns: List<String>,
) {
    BUYERS("buyers", listOf("gln", "customer_id") + ADDRESS_COLUMNS),
    DELIVERY_PARTIES("delivery-parties", listOf("gln") + ADDRESS_COLUMNS),
    ITEMS("items", listOf("code_type", "code", "product_id", "sku")),
    ;

    public companion object {
        public fun of(label: String): CodeKind? = entries.firstOrNull { it.label == label }
    }
}

private val ADDRESS_COLUMNS = listOf("country_code", "postal_code", "region", "city", "line1", "line2")

/** 取引先の 1 つの種類の対応表(検査済み)。キーの重複はない。 */
public sealed interface CodeTable {
    public val kind: CodeKind
    public val size: Int

    public data class Buyers(
        val entries: Map<String, InternalCustomer>,
    ) : CodeTable {
        override val kind: CodeKind get() = CodeKind.BUYERS
        override val size: Int get() = entries.size
    }

    public data class DeliveryParties(
        val entries: Map<String, ShippingAddress>,
    ) : CodeTable {
        override val kind: CodeKind get() = CodeKind.DELIVERY_PARTIES
        override val size: Int get() = entries.size
    }

    public data class Items(
        val entries: Map<ItemCode, InternalItem>,
    ) : CodeTable {
        override val kind: CodeKind get() = CodeKind.ITEMS
        override val size: Int get() = entries.size
    }
}

/**
 * 取り込むファイルの誤り。[row] はファイルの行の番号(見出しが 1)、[column] は列の名前(行全体の誤りなら null)。
 * 値は入れない(顧客の住所などを、ログやコマンドの出力に出さない)。
 */
public data class CodeTableViolation(
    val row: Int,
    val column: String?,
    val reason: String,
)

public sealed interface CodeTableCheck {
    public data class Valid(
        val table: CodeTable,
    ) : CodeTableCheck

    public data class Invalid(
        val violations: List<CodeTableViolation>,
    ) : CodeTableCheck
}

/** 読み込んだファイルの 1 行。[number] はファイルの行の番号(見出しが 1)、値は列の順。 */
public data class CodeRow(
    val number: Int,
    val values: List<String>,
)

/**
 * 取り込むファイル(見出しと行)を検査して、対応表にする。
 * - 見出しは [CodeKind.columns] と同じ並び(過不足・並びの違いは誤り)
 * - GLN は 13 桁の数字、GTIN は 8・12・13・14 桁の数字(EDIFACT の LIN の 7140)、国は ISO 3166-1 の 2 文字の大文字
 * - 必須の値が空・長すぎる・同じキーの重複・空の表(行が 1 つもない)は誤り。`region`・`line2` は空なら null
 */
public object CodeTables {
    private val GLN = Regex("^[0-9]{13}$")
    private val GTIN = Regex("^([0-9]{8}|[0-9]{12,14})$")
    private val COUNTRY = Regex("^[A-Z]{2}$")
    private const val MAX_LENGTH = 70
    private const val MAX_CODE_LENGTH = 35

    // 見出しの誤り・空の表は、行を読む前に返す
    @Suppress("ReturnCount")
    public fun check(
        kind: CodeKind,
        header: List<String>,
        rows: List<CodeRow>,
    ): CodeTableCheck {
        if (header != kind.columns) {
            return CodeTableCheck.Invalid(listOf(CodeTableViolation(1, null, "見出しは ${kind.columns.joinToString(",")} にしてください")))
        }
        if (rows.isEmpty()) return CodeTableCheck.Invalid(listOf(CodeTableViolation(1, null, "行がありません(空の表には入れ替えません)")))
        val violations = mutableListOf<CodeTableViolation>()
        val table =
            when (kind) {
                CodeKind.BUYERS -> CodeTable.Buyers(collect(rows, violations) { it.buyer() })
                CodeKind.DELIVERY_PARTIES -> CodeTable.DeliveryParties(collect(rows, violations) { it.deliveryParty() })
                CodeKind.ITEMS -> CodeTable.Items(collect(rows, violations) { it.item() })
            }
        return if (violations.isEmpty()) CodeTableCheck.Valid(table) else CodeTableCheck.Invalid(violations)
    }

    /** 行ごとに読み、誤りを集める。同じキーの 2 つ目以降は重複の誤り。 */
    private fun <K, V> collect(
        rows: List<CodeRow>,
        violations: MutableList<CodeTableViolation>,
        read: (RowReader) -> Pair<K, V>?,
    ): Map<K, V> {
        val entries = linkedMapOf<K, V>()
        rows.forEach { row ->
            val entry = read(RowReader(row, violations)) ?: return@forEach
            if (entries.containsKey(entry.first)) {
                violations += CodeTableViolation(row.number, null, "同じコードの行が前にあります")
            } else {
                entries[entry.first] = entry.second
            }
        }
        return entries
    }

    /** 1 行の値を列の名前で読む。誤りは [violations] に足し、その行は null にする。 */
    private class RowReader(
        private val row: CodeRow,
        private val violations: MutableList<CodeTableViolation>,
    ) {
        private var columns: List<String> = emptyList()
        private var failed = false

        fun buyer(): Pair<String, InternalCustomer>? =
            using(CodeKind.BUYERS) {
                val gln = matching("gln", GLN, "GLN は 13 桁の数字にしてください")
                val customer = required("customer_id")
                val address = address()
                gln to InternalCustomer(customer, address)
            }

        fun deliveryParty(): Pair<String, ShippingAddress>? =
            using(CodeKind.DELIVERY_PARTIES) { matching("gln", GLN, "GLN は 13 桁の数字にしてください") to address() }

        fun item(): Pair<ItemCode, InternalItem>? =
            using(CodeKind.ITEMS) {
                val code =
                    when (required("code_type")) {
                        "gtin" -> ItemCode.Gtin(matching("code", GTIN, "GTIN は 8・12・13・14 桁の数字にしてください"))
                        "buyer_part_number" -> ItemCode.BuyerPartNumber(required("code", MAX_CODE_LENGTH))
                        else -> fail("code_type", "gtin か buyer_part_number にしてください").let { ItemCode.Gtin("") }
                    }
                code to InternalItem(required("product_id"), required("sku"))
            }

        private fun address(): ShippingAddress =
            ShippingAddress(
                countryCode = matching("country_code", COUNTRY, "国は ISO 3166-1 の 2 文字の大文字にしてください"),
                postalCode = required("postal_code"),
                region = optional("region"),
                city = required("city"),
                line1 = required("line1"),
                line2 = optional("line2"),
            )

        private fun <T> using(
            kind: CodeKind,
            block: () -> T,
        ): T? {
            columns = kind.columns
            if (row.values.size != columns.size) {
                violations += CodeTableViolation(row.number, null, "列の数が見出しと違います")
                return null
            }
            val result = block()
            return if (failed) null else result
        }

        private fun value(column: String): String = row.values[columns.indexOf(column)].trim()

        private fun required(
            column: String,
            maxLength: Int = MAX_LENGTH,
        ): String {
            val value = value(column)
            when {
                value.isEmpty() -> fail(column, "値がありません")
                value.length > maxLength -> fail(column, "$maxLength 文字までにしてください")
            }
            return value
        }

        private fun optional(column: String): String? {
            val value = value(column)
            if (value.length > MAX_LENGTH) fail(column, "$MAX_LENGTH 文字までにしてください")
            return value.ifEmpty { null }
        }

        private fun matching(
            column: String,
            pattern: Regex,
            reason: String,
        ): String {
            val value = value(column)
            if (!pattern.matches(value)) fail(column, reason)
            return value
        }

        private fun fail(
            column: String,
            reason: String,
        ) {
            failed = true
            violations += CodeTableViolation(row.number, column, reason)
        }
    }
}
