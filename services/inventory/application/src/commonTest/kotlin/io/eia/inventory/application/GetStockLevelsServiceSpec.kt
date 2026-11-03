package io.eia.inventory.application

import io.eia.inventory.application.port.inbound.GetStockLevelsUseCase
import io.eia.inventory.application.port.inbound.StockLevels
import io.eia.inventory.application.port.outbound.StockQueries
import io.eia.inventory.application.usecase.GetStockLevelsService
import io.eia.inventory.domain.StockLevel
import io.eia.inventory.domain.StockSnapshot
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private val AT = Instant.fromEpochSeconds(1_800_000_000)

private class FakeQueries(
    private val result: Result<List<StockSnapshot>, DomainError>,
) : StockQueries {
    var asked: Set<String>? = null

    override suspend fun find(skus: Set<String>): Result<List<StockSnapshot>, DomainError> {
        asked = skus
        return result
    }
}

class GetStockLevelsServiceSpec :
    FunSpec({
        val snapshot = StockSnapshot(StockLevel("SKU-1", 10, 3), AT)

        test("ある SKU と、ない SKU を分けて返す。引当できる数は 手持ち - 引当済み") {
            val levels = GetStockLevelsService(FakeQueries(ok(listOf(snapshot))))(listOf("SKU-1", "SKU-X"))

            levels shouldBe ok(StockLevels(listOf(snapshot), listOf("SKU-X")))
            snapshot.level.available shouldBe 7
        }

        test("重複は 1 件として読む") {
            val queries = FakeQueries(ok(listOf(snapshot)))

            GetStockLevelsService(queries)(listOf("SKU-1", "SKU-1"))

            queries.asked shouldBe setOf("SKU-1")
        }

        test("0 件・上限を超える・長すぎる・空の SKU は ValidationError で、読まない") {
            val cases =
                listOf(
                    emptyList(),
                    List(GetStockLevelsUseCase.MAX_SKUS + 1) { "SKU-$it" },
                    listOf("x".repeat(GetStockLevelsUseCase.MAX_SKU_LENGTH + 1)),
                    listOf(""),
                )
            cases.forEach { skus ->
                val queries = FakeQueries(ok(emptyList()))
                val result = GetStockLevelsService(queries)(skus)
                result.shouldBeInstanceOf<Result.Err<DomainError>>().error.shouldBeInstanceOf<ValidationError>()
                queries.asked shouldBe null
            }
        }

        test("上限ちょうどは通す") {
            val skus = List(GetStockLevelsUseCase.MAX_SKUS) { "SKU-$it" }
            GetStockLevelsService(FakeQueries(ok(emptyList())))(skus).shouldBeInstanceOf<Result.Ok<StockLevels>>()
        }

        test("読めなければ、ないことにせず失敗を返す") {
            val failure = UnavailableError("db")
            GetStockLevelsService(FakeQueries(err(failure)))(listOf("SKU-1")) shouldBe err(failure)
        }
    })
