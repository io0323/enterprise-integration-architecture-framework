package io.eia.inventory.adapters.inbound

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// コマンドの型(contracts/avro/inventory。INT-INVENTORY-001)。@SerialName は契約の record のフルネーム(ADR-0025 §1)。

@Serializable
@SerialName("io.eia.events.inventory.StockLine")
public data class StockLineV1(
    val lineNumber: Int,
    val sku: String,
    val quantity: Long,
)

@Serializable
@SerialName("io.eia.events.inventory.ReserveStock")
public data class ReserveStockV1(
    val sagaId: String,
    val orderId: String,
    val lines: List<StockLineV1>,
)

@Serializable
@SerialName("io.eia.events.inventory.ReleaseStock")
public data class ReleaseStockV1(
    val sagaId: String,
    val orderId: String,
)
