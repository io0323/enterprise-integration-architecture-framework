package io.eia.order.adapters.inbound.kafka

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// 参加者の返信の型(contracts/avro/{inventory,payment,shipping}。INT-*-002。ADR-0029 §2・§4)。
// @SerialName は契約の record・enum のフルネーム(ADR-0025 §1)。UNKNOWN は読み手の既定値(新しい版で増えた値)で、受け取ったら DLQ に送る。

@Serializable
@SerialName("io.eia.events.inventory.StockReserved")
public data class StockReservedV1(
    val sagaId: String,
    val orderId: String,
)

@Serializable
@SerialName("io.eia.events.inventory.StockRejectionReason")
public enum class StockRejectionReasonV1 { INSUFFICIENT_STOCK, UNKNOWN_SKU, ALREADY_RELEASED, UNKNOWN }

@Serializable
@SerialName("io.eia.events.inventory.StockReservationRejected")
public data class StockReservationRejectedV1(
    val sagaId: String,
    val orderId: String,
    val reason: StockRejectionReasonV1,
)

@Serializable
@SerialName("io.eia.events.inventory.StockReleaseOutcome")
public enum class StockReleaseOutcomeV1 { RELEASED, NOT_RESERVED, UNKNOWN }

@Serializable
@SerialName("io.eia.events.inventory.StockReleased")
public data class StockReleasedV1(
    val sagaId: String,
    val orderId: String,
    val outcome: StockReleaseOutcomeV1,
)

@Serializable
@SerialName("io.eia.events.payment.PaymentAuthorized")
public data class PaymentAuthorizedV1(
    val sagaId: String,
    val orderId: String,
    val authorizationId: String,
)

@Serializable
@SerialName("io.eia.events.payment.PaymentDeclineReason")
public enum class PaymentDeclineReasonV1 { LIMIT_EXCEEDED, ALREADY_VOIDED, UNKNOWN }

@Serializable
@SerialName("io.eia.events.payment.PaymentDeclined")
public data class PaymentDeclinedV1(
    val sagaId: String,
    val orderId: String,
    val reason: PaymentDeclineReasonV1,
)

@Serializable
@SerialName("io.eia.events.payment.PaymentVoidOutcome")
public enum class PaymentVoidOutcomeV1 { VOIDED, NOT_AUTHORIZED, UNKNOWN }

@Serializable
@SerialName("io.eia.events.payment.PaymentVoided")
public data class PaymentVoidedV1(
    val sagaId: String,
    val orderId: String,
    val outcome: PaymentVoidOutcomeV1,
)

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
public enum class ShipmentRejectionReasonV1 { UNSUPPORTED_DESTINATION, ALREADY_CANCELLED, UNKNOWN }

@Serializable
@SerialName("io.eia.events.shipping.ShipmentRejected")
public data class ShipmentRejectedV1(
    val sagaId: String,
    val orderId: String,
    val reason: ShipmentRejectionReasonV1,
)

@Serializable
@SerialName("io.eia.events.shipping.ShipmentCancelOutcome")
public enum class ShipmentCancelOutcomeV1 { CANCELLED, NOT_ARRANGED, ALREADY_SHIPPED, UNKNOWN }

@Serializable
@SerialName("io.eia.events.shipping.ShipmentCancelled")
public data class ShipmentCancelledV1(
    val sagaId: String,
    val orderId: String,
    val outcome: ShipmentCancelOutcomeV1,
)
