package io.eia.platform.api.problem

import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.ValidationError
import kotlin.time.Duration

/**
 * 返す Problem Details(RFC 9457。ADR-0022 §2)。`correlationId` は応答するときに現在の処理から付ける([respondProblem])。
 *
 * @property errors 検証エラーの項目(422)。`message` は [io.eia.shared.kernel.FieldViolation.reason](値を含めない約束。CODING_STANDARDS)
 * @property retryAfter `Retry-After` に入れる時間(503・429 など)。秒に切り上げる
 */
public class Problem(
    public val type: ProblemType,
    public val errors: List<ProblemFieldError> = emptyList(),
    public val retryAfter: Duration? = null,
) {
    public val status: Int get() = type.status

    override fun toString(): String = "Problem(type=${type.uri}, status=$status, errors=${errors.size})"

    public companion object {
        /**
         * [DomainError] の既定の写し方(ADR-0022 §2)。`detail` は種類ごとの固定の文で、[DomainError.message] は使わない
         * (業務の値や内部の情報を含みうるため)。業務エラーを別の種類にしたいサービスは [ProblemDetailsConfig.mapper] で上書きする。
         *
         * | エラー | 種類 |
         * |---|---|
         * | [ValidationError] | 422 `validation-failed`。`errors` に項目と理由 |
         * | [NotFoundError] | 404 `not-found` |
         * | [ConflictError] | 409 `conflict` |
         * | そのほかの [DomainError.Retryable] | 503 `service-unavailable`。`retryAfter` があれば `Retry-After` |
         * | そのほかの [DomainError.NonRetryable] | 500 `internal-error`(写し方が決まっていない業務エラーは、サービスの実装漏れとして扱う) |
         */
        public fun of(error: DomainError): Problem =
            when (error) {
                is ValidationError -> {
                    Problem(
                        ProblemType.VALIDATION_FAILED,
                        error.violations.map { ProblemFieldError(it.field, it.reason) },
                    )
                }

                is NotFoundError -> {
                    Problem(ProblemType.NOT_FOUND)
                }

                is ConflictError -> {
                    Problem(ProblemType.CONFLICT)
                }

                is DomainError.Retryable -> {
                    Problem(ProblemType.SERVICE_UNAVAILABLE, retryAfter = error.retryAfter)
                }

                else -> {
                    Problem(ProblemType.INTERNAL_ERROR)
                }
            }
    }
}

/** 検証エラーの 1 項目(契約の `Problem.errors[]`)。 */
public data class ProblemFieldError(
    public val field: String,
    public val message: String,
)
