package io.eia.payment.adapters.inbound

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// コマンドの型(contracts/avro/payment。INT-PAYMENT-001)。@SerialName は契約の record のフルネーム(ADR-0025 §1)。

@Serializable
@SerialName("io.eia.events.common.Money")
public data class MoneyV1(
    val minorUnits: Long,
    val currency: String,
)

@Serializable
@SerialName("io.eia.events.payment.AuthorizePayment")
public data class AuthorizePaymentV1(
    val sagaId: String,
    val orderId: String,
    val customerId: String,
    val amount: MoneyV1,
) {
    // 顧客 ID と金額はログに出さない(CLAUDE.md §5 可観測性)
    override fun toString(): String = "AuthorizePaymentV1(sagaId=$sagaId, orderId=$orderId, ***)"
}

@Serializable
@SerialName("io.eia.events.payment.VoidPayment")
public data class VoidPaymentV1(
    val sagaId: String,
    val orderId: String,
)
