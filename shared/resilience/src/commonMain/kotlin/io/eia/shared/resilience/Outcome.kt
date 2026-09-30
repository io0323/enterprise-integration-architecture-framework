package io.eia.shared.resilience

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 1 回の試行の結果を、Circuit Breaker とリトライバジェットの数え方に分類する(ADR-0021 §3)。 */
internal enum class Outcome {
    /** 依存先が応答した。NonRetryable(4xx・業務エラー)も、依存先は動いているので成功に数える。 */
    SUCCESS,

    /** Retryable なエラー(タイムアウトを含む)。 */
    FAILURE,

    /** 数えない。手元で断った呼び出し([ResilienceRejection])と、例外・キャンセルで終わった呼び出し。 */
    IGNORED,
    ;

    companion object {
        fun of(result: Result<*, DomainError>): Outcome =
            when (result) {
                is Result.Ok -> {
                    SUCCESS
                }

                is Result.Err -> {
                    when (result.error) {
                        is ResilienceRejection -> IGNORED
                        is DomainError.Retryable -> FAILURE
                        is DomainError.NonRetryable -> SUCCESS
                    }
                }
            }
    }
}
