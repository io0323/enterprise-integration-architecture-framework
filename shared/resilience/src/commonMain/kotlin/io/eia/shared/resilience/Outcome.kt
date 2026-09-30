package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 1 回の試行の結果を、Circuit Breaker とリトライバジェットの数え方に分類する(ADR-0021 §3)。 */
internal enum class Outcome {
    /** 依存先が応答した。NonRetryable(4xx・業務エラー)も、依存先は動いているので成功に数える。 */
    SUCCESS,

    /** Retryable なエラー(タイムアウトを含む)。 */
    FAILURE,

    /**
     * 数えない。手元で断った呼び出し([ResilienceRejection])、呼び出し元の締め切りで打ち切った試行
     * ([DeadlineSource.CALLER]。ADR-0021 §12)、例外・キャンセルで終わった呼び出し。
     */
    IGNORED,
    ;

    companion object {
        fun of(result: Result<*, DomainError>): Outcome {
            val error = (result as? Result.Err)?.error ?: return SUCCESS
            return when {
                error is ResilienceRejection -> IGNORED
                error is DeadlineExceeded && error.source == DeadlineSource.CALLER -> IGNORED
                error is DomainError.Retryable -> FAILURE
                else -> SUCCESS // NonRetryable: 依存先は応答している
            }
        }
    }
}
