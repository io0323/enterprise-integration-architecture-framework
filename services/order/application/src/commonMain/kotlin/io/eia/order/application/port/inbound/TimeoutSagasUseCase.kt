package io.eia.order.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** DB の時計で期限を過ぎた Saga を、期限切れ(`STEP_TIMED_OUT`)として進める(定期のジョブ。ADR-0029 §6)。 */
public interface TimeoutSagasUseCase {
    /** 期限を過ぎた Saga がなくなるまで、少しずつ処理する。処理した件数を返す。 */
    public suspend operator fun invoke(): Result<Int, DomainError>
}
