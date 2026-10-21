package io.eia.integrationflow.application.usecase

import io.eia.integrationflow.application.port.inbound.TransformRecordUseCase
import io.eia.integrationflow.application.port.inbound.TransformedRecord
import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.FlowMappingError
import io.eia.integrationflow.domain.SourceValue
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map

/** [TransformRecordUseCase] の実装。版 → 写す → キー → 届け先の順に決める(最初の誤りで止める)。 */
public class TransformRecordService : TransformRecordUseCase {
    override fun invoke(
        flow: FlowDefinition,
        record: SourceValue,
    ): Result<TransformedRecord, FlowMappingError> =
        flow.versionOf(record).flatMap { version ->
            flow.map(record).flatMap { mapped ->
                flow.keyOf(mapped).map { key -> TransformedRecord(flow.id, key, version, mapped, flow.route(mapped)) }
            }
        }
}
