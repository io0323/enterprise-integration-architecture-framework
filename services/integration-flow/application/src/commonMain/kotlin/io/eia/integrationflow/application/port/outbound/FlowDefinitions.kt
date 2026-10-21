package io.eia.integrationflow.application.port.outbound

import io.eia.integrationflow.domain.FlowDefinition
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError

/**
 * Flow の定義の置き場(adapters が contracts/flows の YAML を読み、JSON Schema で検証して作る。ADR-0036 §1)。
 * 誤りがあれば、ファイルと場所ごとの違反を返す(起動しない)。
 */
public fun interface FlowDefinitions {
    public fun load(): Result<List<FlowDefinition>, ValidationError>
}
