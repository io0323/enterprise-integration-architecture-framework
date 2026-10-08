package io.eia.legacyorderacl.adapters.outbound

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// `sales.legacy-order.changed.v1` のイベントの型(contracts/avro/sales/LegacyOrderChanged.avsc。INT-SALES-003)。
// avro4k は型とスキーマを record のフルネームで対応させるため、@SerialName を契約の record のフルネームにする(ADR-0025 §1)。

@Serializable
@SerialName("io.eia.events.sales.LegacyOrderChanged")
internal data class LegacyOrderChangedV1(
    val orderNumber: String,
    val customerCode: String,
    val customerName: String,
    val status: LegacyOrderStatusV1,
    val totalAmount: MoneyV1,
    val orderedAt: Instant,
    val legacyUpdatedAt: Instant? = null,
    val source: LegacyChangeSourceV1,
) {
    // 顧客名は個人情報になりうるので、ログに出さない
    override fun toString(): String = "LegacyOrderChangedV1(orderNumber=$orderNumber, status=$status, ***)"
}

@Serializable
@SerialName("io.eia.events.sales.LegacyOrderStatus")
internal enum class LegacyOrderStatusV1 {
    ACCEPTED,
    ALLOCATED,
    SHIPPED,
    CANCELLED,
}

/** 金額(ADR-0012 §2)。 */
@Serializable
@SerialName("io.eia.events.common.Money")
internal data class MoneyV1(
    val minorUnits: Long,
    val currency: String,
)

@Serializable
@SerialName("io.eia.events.sales.LegacyChangeSource")
internal data class LegacyChangeSourceV1(
    val lsn: Long,
    val committedAt: Instant,
    val snapshot: Boolean,
)
