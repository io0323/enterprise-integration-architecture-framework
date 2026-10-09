package io.eia.inventory.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 保持期間を過ぎた記録(終わった引当・印・冪等消費の記録)を消す(定期のジョブ)。 */
public interface PurgeExpiredRecordsUseCase {
    /** 1 回に消す件数の上限ずつ、残りがなくなるまで消す。消した件数(引当の記録・冪等消費の記録)を返す。 */
    public suspend operator fun invoke(): Result<PurgedRecords, DomainError>
}

public data class PurgedRecords(
    public val reservations: Int,
    public val processedMessages: Int,
)
