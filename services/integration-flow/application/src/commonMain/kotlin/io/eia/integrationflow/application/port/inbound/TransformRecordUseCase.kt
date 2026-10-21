package io.eia.integrationflow.application.port.inbound

import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.FlowMappingError
import io.eia.integrationflow.domain.MappedRecord
import io.eia.integrationflow.domain.SourceValue
import io.eia.shared.kernel.Result

/**
 * 元のレコード 1 件を、Flow の定義に従って写し、新旧の判定のキーと版、届け先を決める(Webhook の経路と定期の同期の経路で共通)。
 * 新旧の判定の記録と発行は、呼び出し側(P10 ⑤)が行う。
 */
public fun interface TransformRecordUseCase {
    public operator fun invoke(
        flow: FlowDefinition,
        record: SourceValue,
    ): Result<TransformedRecord, FlowMappingError>
}

/** 写した結果。[key] と [version] で新旧を判定し、[deliver] に出す。 */
public data class TransformedRecord(
    val flowId: String,
    val key: String,
    val version: Long,
    val mapped: MappedRecord,
    val deliver: String,
)
