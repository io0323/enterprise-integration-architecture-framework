package io.eia.b2bgateway.domain.order

import io.eia.b2bgateway.domain.agreement.PartnerAgreement

/**
 * 業務の段階の規則(contracts/files/edi/edifact-orders.v1.yaml の businessRules。ADR-0041 §4)。コードは仕様のコードと一致させる
 * (adapters のテストで確かめる)。取引先に返す文言は仕様から取り、ここには持たない。
 */
public enum class BusinessRule(
    public val code: String,
) {
    UNKNOWN_BUYER("ORD001"),
    UNKNOWN_DELIVERY_PARTY("ORD002"),
    SUPPLIER_IS_NOT_RECIPIENT("ORD003"),
    UNKNOWN_ITEM("ORD004"),
    CURRENCY_NOT_AGREED("ORD005"),
    DELIVERY_BEFORE_ORDER_DATE("ORD006"),
    DUPLICATE_ORDER_NUMBER("ORD007"),
    LINE_COUNT_MISMATCH("ORD008"),
    DUPLICATE_LINE_NUMBER("ORD009"),
    INVALID_QUANTITY("ORD010"),
    INVALID_PRICE("ORD011"),
}

/** 社内の商品(order の API の明細の productId と sku)。 */
public data class InternalItem(
    public val productId: String,
    public val sku: String,
)

/**
 * 取引先ごとのコードの対応表を引いた結果(P12 ⑤ で表を作る)。対応がないコードはキーに入らない。
 *
 * @property buyers 買い手の GLN → 社内の顧客の ID
 * @property deliveryParties 届け先の GLN → 社内の届け先の参照
 * @property items 取引先の商品のコード → 社内の商品
 */
public data class CodeResolution(
    public val buyers: Map<String, String> = emptyMap(),
    public val deliveryParties: Map<String, String> = emptyMap(),
    public val items: Map<ItemCode, InternalItem> = emptyMap(),
)

/**
 * 業務の検査の材料。[orderNumberReceived] は、同じ取引先から同じ注文番号を前に受け付けていたか(別の交換で届いた同じ注文。
 * 同じ交換の中の重複も呼び出し側が数える)。
 */
public data class BusinessContext(
    public val agreement: PartnerAgreement,
    public val resolution: CodeResolution,
    public val orderNumberReceived: Boolean,
)

/** 業務の規則で注文を検査する。違反がなければ空。違反は仕様の規則の順に並べる。 */
public object OrderRules {
    private val POSITIVE_INTEGER = Regex("^[1-9][0-9]*$")
    private val DECIMAL = Regex("^(0|[1-9][0-9]*)(\\.(?<fraction>[0-9]+))?$")

    public fun check(
        order: PurchaseOrder,
        context: BusinessContext,
    ): List<BusinessRule> {
        val violations = partyAndCodeViolations(order, context) + consistencyViolations(order, context)
        return BusinessRule.entries.filter { it in violations }
    }

    /** 対応表と合意(買い手・届け先・売り手・商品・通貨)。 */
    private fun partyAndCodeViolations(
        order: PurchaseOrder,
        context: BusinessContext,
    ): Set<BusinessRule> =
        buildSet {
            val resolution = context.resolution
            if (order.buyer !in resolution.buyers) add(BusinessRule.UNKNOWN_BUYER)
            if (order.deliveryParty != null && order.deliveryParty !in resolution.deliveryParties) add(BusinessRule.UNKNOWN_DELIVERY_PARTY)
            if (order.supplier != context.agreement.ownGln) add(BusinessRule.SUPPLIER_IS_NOT_RECIPIENT)
            if (order.lines.any { it.itemCode !in resolution.items }) add(BusinessRule.UNKNOWN_ITEM)
            if (currency(order, context) == null) add(BusinessRule.CURRENCY_NOT_AGREED)
        }

    /** 注文の中の整合(日付・注文番号の重複・件数・明細番号・数量・単価)。 */
    private fun consistencyViolations(
        order: PurchaseOrder,
        context: BusinessContext,
    ): Set<BusinessRule> =
        buildSet {
            val currency = currency(order, context)
            if (order.requestedDelivery != null &&
                order.requestedDelivery < order.documentDate
            ) {
                add(BusinessRule.DELIVERY_BEFORE_ORDER_DATE)
            }
            if (context.orderNumberReceived) add(BusinessRule.DUPLICATE_ORDER_NUMBER)
            if (order.declaredLineCount != null &&
                order.declaredLineCount != order.lines.size.toString()
            ) {
                add(BusinessRule.LINE_COUNT_MISMATCH)
            }
            if (order.lines
                    .map { it.lineNumber }
                    .toSet()
                    .size != order.lines.size
            ) {
                add(BusinessRule.DUPLICATE_LINE_NUMBER)
            }
            if (order.lines.any { !POSITIVE_INTEGER.matches(it.quantity) }) add(BusinessRule.INVALID_QUANTITY)
            if (currency != null && order.lines.any { !validPrice(it.unitPrice, currency.minorUnitDigits) }) add(BusinessRule.INVALID_PRICE)
        }

    private fun currency(
        order: PurchaseOrder,
        context: BusinessContext,
    ) = context.agreement.currencies.firstOrNull { it.code == order.currency }

    /** 単価は 0 以上で、小数部が通貨の小数桁数以内(負の値・桁の超過は ORD011)。 */
    private fun validPrice(
        price: String,
        minorUnitDigits: Int,
    ): Boolean {
        val match = DECIMAL.matchEntire(price) ?: return false
        return (match.groups["fraction"]?.value?.length ?: 0) <= minorUnitDigits
    }
}
