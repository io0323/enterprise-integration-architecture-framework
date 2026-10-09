package io.eia.inventory.adapters.out.outbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 返事のイベントの型(contracts/avro/inventory。INT-INVENTORY-002)。avro4k は record のフルネームで型とスキーマを対応させるため、
// @SerialName を契約の record・enum のフルネームにする(ADR-0025 §1)。enum の UNKNOWN は読み手の既定値で、送り手は使わない(ADR-0029 §4)。

@Serializable
@SerialName("io.eia.events.inventory.StockReserved")
public data class StockReservedV1(
    val sagaId: String,
    val orderId: String,
)

@Serializable
@SerialName("io.eia.events.inventory.StockRejectionReason")
public enum class StockRejectionReasonV1 {
    INSUFFICIENT_STOCK,
    UNKNOWN_SKU,
    ALREADY_RELEASED,
    UNKNOWN,
}

@Serializable
@SerialName("io.eia.events.inventory.StockReservationRejected")
public data class StockReservationRejectedV1(
    val sagaId: String,
    val orderId: String,
    val reason: StockRejectionReasonV1,
)

@Serializable
@SerialName("io.eia.events.inventory.StockReleaseOutcome")
public enum class StockReleaseOutcomeV1 {
    RELEASED,
    NOT_RESERVED,
    UNKNOWN,
}

@Serializable
@SerialName("io.eia.events.inventory.StockReleased")
public data class StockReleasedV1(
    val sagaId: String,
    val orderId: String,
    val outcome: StockReleaseOutcomeV1,
)
