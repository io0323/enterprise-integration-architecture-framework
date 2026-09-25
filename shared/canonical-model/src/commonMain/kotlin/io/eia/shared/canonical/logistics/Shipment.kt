package io.eia.shared.canonical.logistics

import io.eia.shared.canonical.catalog.ProductId
import io.eia.shared.canonical.common.Address
import io.eia.shared.canonical.common.Validatable
import io.eia.shared.canonical.common.validating
import io.eia.shared.canonical.sales.OrderId
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@Serializable
@JvmInline
public value class ShipmentId(
    public val value: String,
)

@Serializable
public enum class ShipmentStatus {
    PREPARING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
}

@Serializable
public data class ShipmentItem(
    val lineNumber: Int,
    val productId: ProductId,
    val sku: String,
    val quantity: Long,
) : Validatable<ShipmentItem> {
    override fun validate(): Result<ShipmentItem, ValidationError> =
        validating(this) {
            check(lineNumber >= 1, "lineNumber") { "1 以上です" }
            check(productId.value.isNotBlank(), "productId") { "必須です" }
            check(sku.isNotBlank(), "sku") { "必須です" }
            check(quantity >= 1, "quantity") { "1 以上です" }
        }
}

/**
 * 出荷。発送済み([ShipmentStatus.SHIPPED] / [ShipmentStatus.DELIVERED])なら運送会社・追跡番号・発送日時が必須で、
 * 配達済みなら配達日時が発送日時以降であること。
 */
@Serializable
public data class Shipment(
    val id: ShipmentId,
    val orderId: OrderId,
    val status: ShipmentStatus,
    val destination: Address,
    val items: List<ShipmentItem>,
    val carrier: String? = null,
    val trackingNumber: String? = null,
    val shippedAt: Instant? = null,
    val deliveredAt: Instant? = null,
) : Validatable<Shipment> {
    override fun validate(): Result<Shipment, ValidationError> =
        validating(this) {
            check(id.value.isNotBlank(), "id") { "必須です" }
            check(orderId.value.isNotBlank(), "orderId") { "必須です" }
            check(items.isNotEmpty(), "items") { "1 件以上必要です" }
            items.forEachIndexed { i, item -> include("items[$i]", item.validate()) }
            include("destination", destination.validate())
            val dispatched = status == ShipmentStatus.SHIPPED || status == ShipmentStatus.DELIVERED
            if (dispatched) {
                check(!carrier.isNullOrBlank(), "carrier") { "発送済みなら必須です" }
                check(!trackingNumber.isNullOrBlank(), "trackingNumber") { "発送済みなら必須です" }
                check(shippedAt != null, "shippedAt") { "発送済みなら必須です" }
            }
            if (status == ShipmentStatus.DELIVERED) {
                check(deliveredAt != null, "deliveredAt") { "配達済みなら必須です" }
            }
            if (shippedAt != null && deliveredAt != null) {
                check(deliveredAt >= shippedAt, "deliveredAt") { "shippedAt 以降です" }
            }
        }
}
