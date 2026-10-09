package io.eia.shipping.adapters.out.outbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// 返事のイベントの型(contracts/avro/shipping。INT-SHIPPING-002)。@SerialName は契約の record・enum のフルネーム(ADR-0025 §1)。
// enum の UNKNOWN は読み手の既定値で、送り手は使わない(ADR-0029 §4)。

@Serializable
@SerialName("io.eia.events.shipping.ShipmentShipped")
public data class ShipmentShippedV1(
    val sagaId: String,
    val orderId: String,
    val shipmentId: String,
    val shippedAt: Instant,
)

@Serializable
@SerialName("io.eia.events.shipping.ShipmentRejectionReason")
public enum class ShipmentRejectionReasonV1 {
    UNSUPPORTED_DESTINATION,
    ALREADY_CANCELLED,
    UNKNOWN,
}

@Serializable
@SerialName("io.eia.events.shipping.ShipmentRejected")
public data class ShipmentRejectedV1(
    val sagaId: String,
    val orderId: String,
    val reason: ShipmentRejectionReasonV1,
)

@Serializable
@SerialName("io.eia.events.shipping.ShipmentCancelOutcome")
public enum class ShipmentCancelOutcomeV1 {
    CANCELLED,
    NOT_ARRANGED,
    ALREADY_SHIPPED,
    UNKNOWN,
}

@Serializable
@SerialName("io.eia.events.shipping.ShipmentCancelled")
public data class ShipmentCancelledV1(
    val sagaId: String,
    val orderId: String,
    val outcome: ShipmentCancelOutcomeV1,
)
