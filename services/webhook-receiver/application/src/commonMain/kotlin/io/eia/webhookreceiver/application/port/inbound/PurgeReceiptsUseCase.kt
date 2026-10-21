package io.eia.webhookreceiver.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 保持期間を過ぎた受け取りの記録を消す。消した件数を返す。 */
public fun interface PurgeReceiptsUseCase {
    public suspend operator fun invoke(): Result<Int, DomainError>
}
