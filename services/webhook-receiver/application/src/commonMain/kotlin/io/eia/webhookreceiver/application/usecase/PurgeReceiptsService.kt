package io.eia.webhookreceiver.application.usecase

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import io.eia.webhookreceiver.application.port.inbound.PurgeReceiptsUseCase
import io.eia.webhookreceiver.application.port.outbound.WebhookReceipts
import io.eia.webhookreceiver.domain.ReceiptRetention

/** 保持期間([retention]。既定 30 日)を過ぎた受け取りの記録を、[batchSize] 件ずつ、少なく消えるまで消す(長いロックを避ける)。 */
public class PurgeReceiptsService(
    private val receipts: WebhookReceipts,
    private val retention: ReceiptRetention = ReceiptRetention.DEFAULT,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : PurgeReceiptsUseCase {
    init {
        require(batchSize >= 1) { "batchSize は 1 以上にしてください: $batchSize" }
    }

    override suspend fun invoke(): Result<Int, DomainError> {
        var total = 0
        while (true) {
            when (val deleted = receipts.purgeOlderThan(retention.value, batchSize)) {
                is Result.Err -> {
                    return deleted
                }

                is Result.Ok -> {
                    total += deleted.value
                    if (deleted.value < batchSize) return ok(total)
                }
            }
        }
    }

    public companion object {
        public const val DEFAULT_BATCH_SIZE: Int = 1000
    }
}
