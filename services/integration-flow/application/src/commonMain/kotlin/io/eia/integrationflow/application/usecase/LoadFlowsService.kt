package io.eia.integrationflow.application.usecase

import io.eia.integrationflow.application.port.inbound.LoadFlowsUseCase
import io.eia.integrationflow.application.port.outbound.FlowDefinitions
import io.eia.integrationflow.domain.FlowCatalog
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.flatMap

/** [LoadFlowsUseCase] の実装。置き場から読み、一覧として整合を確かめる(同じ ID の重複)。 */
public class LoadFlowsService(
    private val definitions: FlowDefinitions,
) : LoadFlowsUseCase {
    override fun invoke(): Result<FlowCatalog, ValidationError> = definitions.load().flatMap { FlowCatalog.of(it) }
}
