package io.eia.batchetl.application

import io.eia.batchetl.application.port.inbound.IngestOrderEventsUseCase
import io.eia.batchetl.application.port.inbound.IngestReport
import io.eia.batchetl.application.port.outbound.MartSummaries
import io.eia.batchetl.application.port.outbound.OrderMart
import io.eia.batchetl.application.port.outbound.OrderSummarySource
import io.eia.batchetl.application.port.outbound.ReconciliationLog
import io.eia.batchetl.application.usecase.ReconcileOrderMartService
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.DailySummary
import io.eia.batchetl.domain.DayReconciliation
import io.eia.batchetl.domain.ReconcileOutcome
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val DIGEST = "a".repeat(64)

/** 分析の側: 作り直すと [fixedAfterRebuild] の日は order-service と一致するようになる。 */
private class FakeReconcileMart(
    val drifted: MutableSet<BusinessDay> = mutableSetOf(),
    val fixedAfterRebuild: Set<BusinessDay> = emptySet(),
    val notBuilt: Set<BusinessDay> = emptySet(),
) : OrderMart,
    MartSummaries,
    ReconciliationLog {
    val rebuilt = mutableListOf<BusinessDay>()
    var recorded: List<DayReconciliation> = emptyList()

    override suspend fun dirtyDays(): Result<List<BusinessDay>, DomainError> = ok(emptyList())

    override suspend fun rebuild(day: BusinessDay): Result<Int, DomainError> {
        rebuilt += day
        if (day in fixedAfterRebuild) drifted -= day
        return ok(1)
    }

    override suspend fun summary(day: BusinessDay): Result<DailySummary?, DomainError> =
        ok(if (day in notBuilt) null else DailySummary(day, if (day in drifted) 1 else 2, DIGEST))

    override suspend fun record(
        runDay: BusinessDay,
        results: List<DayReconciliation>,
    ): Result<Unit, DomainError> {
        recorded = results
        return ok(Unit)
    }
}

private class FakeSummarySource(
    val unavailable: Set<BusinessDay> = emptySet(),
) : OrderSummarySource {
    override suspend fun summary(day: BusinessDay): Result<DailySummary, DomainError> =
        if (day in unavailable) err(UnavailableError("order-service")) else ok(DailySummary(day, 2, DIGEST))
}

private class CountingIngest : IngestOrderEventsUseCase {
    var calls = 0

    override suspend fun invoke(): Result<IngestReport, DomainError> {
        calls++
        return ok(IngestReport(0, 0))
    }
}

class ReconcileOrderMartSpec :
    FunSpec({
        val runDay =
            when (val parsed = BusinessDay.parse("2026-10-19")) {
                is Result.Ok -> parsed.value
                is Result.Err -> fail("$parsed")
            }

        fun <T> Result<T, DomainError>.ok(): T =
            when (this) {
                is Result.Ok -> value
                is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
            }

        test("比べる前に取り込み、38 日分を比べて記録する。一致しない日は取り込み直して作り直し、比べ直す") {
            val recent = runDay.minusDays(3)
            val mart = FakeReconcileMart(drifted = mutableSetOf(recent), fixedAfterRebuild = setOf(recent))
            val ingest = CountingIngest()

            val report = ReconcileOrderMartService(ingest, mart, mart, FakeSummarySource(), mart)(runDay).ok()

            report.days.size shouldBe 38
            report.days.all { it.outcome == ReconcileOutcome.MATCH } shouldBe true
            mart.rebuilt shouldBe listOf(recent)
            ingest.calls shouldBe 2
            mart.recorded shouldBe report.days
        }

        test("比べ直しても一致しなければ DRIFT。作り直す範囲(31 日)より古い日は作り直さずに比べ直す") {
            val old = runDay.minusDays(35)
            val recent = runDay.minusDays(2)
            val mart = FakeReconcileMart(drifted = mutableSetOf(old, recent))

            val report = ReconcileOrderMartService(CountingIngest(), mart, mart, FakeSummarySource(), mart)(runDay).ok()

            report.days.filter { it.outcome == ReconcileOutcome.DRIFT }.map { it.day } shouldBe listOf(old, recent)
            mart.rebuilt shouldBe listOf(recent)
        }

        test("order-service に問い合わせられない日は UNVERIFIED にして続け、記録してから一時的な失敗を返す。まだ作っていない日も UNVERIFIED") {
            val down = runDay.minusDays(5)
            val notBuilt = runDay.minusDays(1)
            val mart = FakeReconcileMart(notBuilt = setOf(notBuilt))

            val result = ReconcileOrderMartService(CountingIngest(), mart, mart, FakeSummarySource(setOf(down)), mart)(runDay)

            result.shouldBeInstanceOf<Result.Err<DomainError>>().error.shouldBeInstanceOf<UnavailableError>()
            mart.recorded.filter { it.outcome == ReconcileOutcome.UNVERIFIED }.map { it.day } shouldBe listOf(down, notBuilt)
            mart.recorded.size shouldBe 38

            val built = FakeReconcileMart(notBuilt = setOf(notBuilt))
            ReconcileOrderMartService(CountingIngest(), built, built, FakeSummarySource(), built)(runDay).ok().days.count {
                it.outcome == ReconcileOutcome.UNVERIFIED
            } shouldBe 1
        }
    })
