package io.eia.integrationflow.application.port.inbound

import io.eia.integrationflow.domain.FlowCatalog
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError

/** 起動時に Flow の定義を読み込み、確かめる(誤りがあれば起動しない。ROADMAP P10 の DoD)。 */
public fun interface LoadFlowsUseCase {
    public operator fun invoke(): Result<FlowCatalog, ValidationError>
}
