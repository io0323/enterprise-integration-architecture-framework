package io.eia.payment.adapters.out.outbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 返事のイベントの型(contracts/avro/payment。INT-PAYMENT-002)。@SerialName は契約の record・enum のフルネーム(ADR-0025 §1)。
// enum の UNKNOWN は読み手の既定値で、送り手は使わない(ADR-0029 §4)。

@Serializable
@SerialName("io.eia.events.payment.PaymentAuthorized")
public data class PaymentAuthorizedV1(
    val sagaId: String,
    val orderId: String,
    val authorizationId: String,
)

@Serializable
@SerialName("io.eia.events.payment.PaymentDeclineReason")
public enum class PaymentDeclineReasonV1 {
    LIMIT_EXCEEDED,
    ALREADY_VOIDED,
    UNKNOWN,
}

@Serializable
@SerialName("io.eia.events.payment.PaymentDeclined")
public data class PaymentDeclinedV1(
    val sagaId: String,
    val orderId: String,
    val reason: PaymentDeclineReasonV1,
)

@Serializable
@SerialName("io.eia.events.payment.PaymentVoidOutcome")
public enum class PaymentVoidOutcomeV1 {
    VOIDED,
    NOT_AUTHORIZED,
    UNKNOWN,
}

@Serializable
@SerialName("io.eia.events.payment.PaymentVoided")
public data class PaymentVoidedV1(
    val sagaId: String,
    val orderId: String,
    val outcome: PaymentVoidOutcomeV1,
)
