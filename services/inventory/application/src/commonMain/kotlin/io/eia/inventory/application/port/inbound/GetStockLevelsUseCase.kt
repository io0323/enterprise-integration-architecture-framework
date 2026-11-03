package io.eia.inventory.application.port.inbound

import io.eia.inventory.domain.StockSnapshot
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** SKU ごとの在庫を、まとめて読む(INT-INVENTORY-003。ADR-0042 §4)。読み取りだけで、在庫を変えない。 */
public interface GetStockLevelsUseCase {
    /** [skus] は 1〜[MAX_SKUS] 件(重複は 1 件として扱う)で、1 件は 1〜[MAX_SKU_LENGTH] 文字。違反は `ValidationError`。 */
    public suspend operator fun invoke(skus: List<String>): Result<StockLevels, DomainError>

    public companion object {
        public const val MAX_SKUS: Int = 500
        public const val MAX_SKU_LENGTH: Int = 64
    }
}

/**
 * @property found 在庫の行がある SKU
 * @property unknownSkus 在庫の行がない SKU(読めなかったことと区別する)
 */
public data class StockLevels(
    public val found: List<StockSnapshot>,
    public val unknownSkus: List<String>,
)
