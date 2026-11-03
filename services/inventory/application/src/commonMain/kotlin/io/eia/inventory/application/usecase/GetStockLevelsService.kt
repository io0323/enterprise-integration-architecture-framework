package io.eia.inventory.application.usecase

import io.eia.inventory.application.port.inbound.GetStockLevelsUseCase
import io.eia.inventory.application.port.inbound.StockLevels
import io.eia.inventory.application.port.outbound.StockQueries
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.map

/** 在庫の照会。入力の検査だけを行い、[StockQueries] で読む(ADR-0042 §4)。 */
public class GetStockLevelsService(
    private val queries: StockQueries,
) : GetStockLevelsUseCase {
    override suspend fun invoke(skus: List<String>): Result<StockLevels, DomainError> {
        val unique = skus.toSet()
        val reason =
            when {
                unique.isEmpty() -> {
                    "1 件以上にしてください"
                }

                unique.size > GetStockLevelsUseCase.MAX_SKUS -> {
                    "${GetStockLevelsUseCase.MAX_SKUS} 件以下にしてください"
                }

                unique.any { it.isEmpty() || it.length > GetStockLevelsUseCase.MAX_SKU_LENGTH } -> {
                    "1 件は 1〜${GetStockLevelsUseCase.MAX_SKU_LENGTH} 文字にしてください"
                }

                else -> {
                    null
                }
            }
        if (reason != null) return err(ValidationError.of("skus", reason))
        return queries.find(unique).map { found ->
            val known = found.map { it.level.sku }.toSet()
            StockLevels(found, unique.filterNot { it in known })
        }
    }
}
