package io.eia.platform.api.problem

import io.eia.shared.kernel.DomainError

/** [installProblemDetails] の設定。 */
public class ProblemDetailsConfig {
    /**
     * 業務エラーの写し方。`null` を返すと既定([Problem.of])で写す。
     * 例: 在庫不足の `ConflictError` を、サービスで決めた種類にする。
     */
    public var mapper: ((DomainError) -> Problem?)? = null
}
