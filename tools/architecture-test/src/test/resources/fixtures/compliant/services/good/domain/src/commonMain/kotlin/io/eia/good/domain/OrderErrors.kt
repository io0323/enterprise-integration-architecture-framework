package io.eia.good.domain

import io.eia.shared.kernel.DomainError

data class OutOfStock(
    val sku: String,
) : DomainError.NonRetryable {
    override val code = "out_of_stock"
    override val message = "在庫不足"
}

// Retryable だけを継承したインターフェースと、その実装
interface TransientFailure : DomainError.Retryable

object PaymentTimeout : TransientFailure {
    override val code = "payment_timeout"
    override val message = "決済がタイムアウトしました"
}
