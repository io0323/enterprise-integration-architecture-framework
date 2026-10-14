package io.eia.order.application

import io.eia.order.application.port.inbound.DailyOrderSummary
import io.eia.order.application.port.outbound.OrderCount
import io.eia.order.application.port.outbound.OrderSummaries
import io.eia.order.application.usecase.GetDailyOrderSummaryService
import io.eia.order.domain.BusinessDay
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

private class RecordingSummaries(
    var result: Result<OrderCount, DomainError> = ok(OrderCount(3, "ab".repeat(32))),
) : OrderSummaries {
    val asked = mutableListOf<Pair<Instant, Instant>>()

    override suspend fun count(
        from: Instant,
        until: Instant,
    ): Result<OrderCount, DomainError> = result.also { asked += from to until }
}

private fun day(text: String): BusinessDay = (BusinessDay.parse(text) as? Result.Ok)?.value ?: fail("読めません: $text")

class DailyOrderSummarySpec :
    FunSpec({
        test("業務の 1 日(日本時間)の区間で集計し、区間と件数とハッシュを返す") {
            val summaries = RecordingSummaries()
            GetDailyOrderSummaryService(summaries)(day("2026-10-14")) shouldBe
                ok(
                    DailyOrderSummary(
                        day("2026-10-14"),
                        Instant.parse("2026-10-13T15:00:00Z"),
                        Instant.parse("2026-10-14T15:00:00Z"),
                        3,
                        "ab".repeat(32),
                    ),
                )
            summaries.asked shouldBe listOf(Instant.parse("2026-10-13T15:00:00Z") to Instant.parse("2026-10-14T15:00:00Z"))
        }

        test("集計の失敗はそのまま返す") {
            val failure = UnavailableError("DB に接続できない")
            GetDailyOrderSummaryService(RecordingSummaries(err(failure)))(day("2026-10-14")) shouldBe err(failure)
        }
    })
