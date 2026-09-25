package io.eia.sample.domain

import io.eia.shared.kernel.DomainError

// 違反: Retryable と NonRetryable の両方を直接実装する
data class BothKinds(
    override val code: String,
    override val message: String,
) : DomainError.Retryable,
    DomainError.NonRetryable

// 違反: Retryable を継承したインターフェース経由で、間接的に両方を実装する
interface TransientBusinessError : DomainError.Retryable

object IndirectBoth : TransientBusinessError, DomainError.NonRetryable {
    override val code = "indirect"
    override val message = "indirect"
}
