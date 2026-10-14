package io.eia.batchetl.domain

import kotlin.time.Instant

/** 注文の状態(契約の `io.eia.events.sales.OrderStatus`)。 */
public enum class OrderStatus {
    PLACED,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
}

/** 届け先の住所(個人情報)。取り込みの前に [ShippingArea] に変える(ETL。ADR-0031 §4)。 */
public data class ShippingAddress(
    val countryCode: String,
    val postalCode: String,
    val region: String?,
    val city: String,
    val line1: String,
    val line2: String?,
) {
    // 住所は個人情報なので、ログに出さない
    override fun toString(): String = "ShippingAddress(countryCode=$countryCode, ***)"
}

/**
 * 分析に入れてよい、届け先の地域(住所の個人情報を落としたもの。ADR-0031 §4)。
 *
 * @property postalPrefix 郵便番号の先頭 3 桁(数字だけを数える。地域の粒度。番地まで特定しない)。3 桁に満たなければ null
 */
public data class ShippingArea(
    val countryCode: String,
    val region: String?,
    val postalPrefix: String?,
) {
    public companion object {
        public const val POSTAL_PREFIX_DIGITS: Int = 3

        /** 住所から地域だけを残す。市区町村・番地・建物は落とす。 */
        public fun of(address: ShippingAddress): ShippingArea {
            val digits = address.postalCode.filter { it in '0'..'9' }
            return ShippingArea(
                countryCode = address.countryCode,
                region = address.region?.takeIf { it.isNotBlank() },
                postalPrefix = digits.take(POSTAL_PREFIX_DIGITS).takeIf { it.length == POSTAL_PREFIX_DIGITS },
            )
        }
    }
}

/** 取り込む注文のイベント(INT-SALES-002。ADR-0031 §2)。 */
public sealed interface OrderEvent {
    public val orderId: String

    /** `sales.order.created.v1`。住所を含むので、そのままは取り込まない([toRecord] で地域に変える)。 */
    public data class Placed(
        override val orderId: String,
        val customerId: String,
        val orderedAt: Instant,
        val totalMinorUnits: Long,
        val currency: String,
        val lineCount: Int,
        val shippingAddress: ShippingAddress,
    ) : OrderEvent

    /** `sales.order.status-changed.v1`。[version] の大きい方が新しい(届く順に依存しない)。 */
    public data class StatusChanged(
        override val orderId: String,
        val status: OrderStatus,
        val version: Long,
        val changedAt: Instant,
    ) : OrderEvent

    /** `sales.order.cancelled.v1`(取消の理由。状態の遷移は [StatusChanged] でも届く)。 */
    public data class Cancelled(
        override val orderId: String,
        val cancelledAt: Instant,
        val reason: String,
    ) : OrderEvent
}

/** 分析の DB に取り込む形(個人情報を含まない)。 */
public sealed interface OrderRecord {
    public val orderId: String

    /** @property day 受け付けた業務の日(日次のパーティション) */
    public data class Placed(
        override val orderId: String,
        val customerId: String,
        val orderedAt: Instant,
        val day: BusinessDay,
        val totalMinorUnits: Long,
        val currency: String,
        val lineCount: Int,
        val area: ShippingArea,
    ) : OrderRecord

    public data class StatusChanged(
        override val orderId: String,
        val status: OrderStatus,
        val version: Long,
        val changedAt: Instant,
    ) : OrderRecord

    public data class Cancelled(
        override val orderId: String,
        val cancelledAt: Instant,
        val reason: String,
    ) : OrderRecord
}

/** 取り込む前の変換(ETL): 住所を地域にし、業務の日を決める。 */
public fun OrderEvent.toRecord(): OrderRecord =
    when (this) {
        is OrderEvent.Placed -> {
            OrderRecord.Placed(
                orderId = orderId,
                customerId = customerId,
                orderedAt = orderedAt,
                day = BusinessDay.of(orderedAt),
                totalMinorUnits = totalMinorUnits,
                currency = currency,
                lineCount = lineCount,
                area = ShippingArea.of(shippingAddress),
            )
        }

        is OrderEvent.StatusChanged -> {
            OrderRecord.StatusChanged(orderId, status, version, changedAt)
        }

        is OrderEvent.Cancelled -> {
            OrderRecord.Cancelled(orderId, cancelledAt, reason)
        }
    }
