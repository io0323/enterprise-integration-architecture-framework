package io.eia.b2bgateway.domain.order

import io.eia.b2bgateway.domain.edifact.Segment

/**
 * 受注の文書(ORDERS)の 1 つのメッセージから取り出した注文(contracts/files/edi/edifact-orders.v1.yaml)。
 * 取引先の表し方(GLN・GTIN・取引先の商品コード)のまま持ち、社内の ID への対応は業務の段階([OrderRules])と、社内への取り込み(P12 ⑤)で行う。
 * 数値(数量・単価)は、小数点を `.` にそろえた文字列のまま持つ(業務の規則で検査する)。
 */
public data class PurchaseOrder(
    public val orderNumber: String,
    public val documentDate: String,
    public val requestedDelivery: String?,
    public val buyer: String,
    public val supplier: String,
    public val deliveryParty: String?,
    public val currency: String,
    public val lines: List<OrderLine>,
    public val declaredLineCount: String?,
)

public data class OrderLine(
    public val lineNumber: String,
    public val gtin: String,
    public val buyerPartNumber: String?,
    public val quantity: String,
    public val unitPrice: String,
) {
    /** 対応表を引くときのコード。取引先の商品コード(PIA の BP)があればそれ、なければ GTIN(ADR-0041 §1)。 */
    public val itemCode: ItemCode get() = buyerPartNumber?.let { ItemCode.BuyerPartNumber(it) } ?: ItemCode.Gtin(gtin)
}

/** 取引先が商品を表すコード。 */
public sealed interface ItemCode {
    public val value: String

    public data class Gtin(
        override val value: String,
    ) : ItemCode

    public data class BuyerPartNumber(
        override val value: String,
    ) : ItemCode
}

/**
 * スキーマの段階を通ったメッセージ(UNH〜UNT)から注文を取り出す。並びと修飾子は検査済みなので、ここでは位置で読む
 * (LIN が来るたびに明細を 1 つ始め、続く PIA・QTY・PRI をその明細に付ける)。
 */
public object PurchaseOrders {
    private const val ORDER_NUMBER = 2
    private const val ITEM = 3

    public fun extract(
        segments: List<Segment>,
        decimal: Char,
    ): PurchaseOrder {
        val dates = mutableMapOf<String, String>()
        val parties = mutableMapOf<String, String>()
        val lines = mutableListOf<LineBuilder>()
        var orderNumber = ""
        var currency = ""
        var count: String? = null
        segments.forEach { segment ->
            when (segment.tag) {
                "BGM" -> orderNumber = segment.value(ORDER_NUMBER)
                "DTM" -> dates[segment.value(1, 1)] = segment.value(1, 2)
                "NAD" -> parties[segment.value(1)] = segment.value(2, 1)
                "CUX" -> currency = segment.value(1, 2)
                "LIN" -> lines += LineBuilder(segment.value(1), segment.value(ITEM, 1))
                "PIA" -> lines.last().buyerPartNumber = segment.value(2, 1)
                "QTY" -> lines.last().quantity = number(segment.value(1, 2), decimal)
                "PRI" -> lines.last().unitPrice = number(segment.value(1, 2), decimal)
                "CNT" -> count = segment.value(1, 2)
            }
        }
        return PurchaseOrder(
            orderNumber = orderNumber,
            documentDate = dates[DOCUMENT_DATE].orEmpty(),
            requestedDelivery = dates[DELIVERY_DATE],
            buyer = parties[BUYER].orEmpty(),
            supplier = parties[SUPPLIER].orEmpty(),
            deliveryParty = parties[DELIVERY_PARTY],
            currency = currency,
            lines = lines.map { it.build() },
            declaredLineCount = count,
        )
    }

    private fun number(
        value: String,
        decimal: Char,
    ): String = value.replace(decimal, '.')

    private class LineBuilder(
        val lineNumber: String,
        val gtin: String,
    ) {
        var buyerPartNumber: String? = null
        var quantity = ""
        var unitPrice = ""

        fun build(): OrderLine = OrderLine(lineNumber, gtin, buyerPartNumber, quantity, unitPrice)
    }

    // 修飾子(contracts/files/edi/edifact-orders.v1.yaml の DTM の 2005 と NAD の 3035)
    private const val DOCUMENT_DATE = "137"
    private const val DELIVERY_DATE = "2"
    private const val BUYER = "BY"
    private const val SUPPLIER = "SU"
    private const val DELIVERY_PARTY = "DP"
}
