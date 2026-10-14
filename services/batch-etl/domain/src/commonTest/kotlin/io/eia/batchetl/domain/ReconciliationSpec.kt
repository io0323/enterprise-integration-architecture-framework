package io.eia.batchetl.domain

import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReconciliationSpec :
    FunSpec({
        val day =
            when (val parsed = BusinessDay.parse("2026-10-13")) {
                is Result.Ok -> parsed.value
                is Result.Err -> fail("$parsed")
            }
        val digest = "a".repeat(64)

        test("件数とハッシュが両方一致すれば MATCH、どちらかが違えば DRIFT、片方がなければ UNVERIFIED") {
            DayReconciliation(day, DailySummary(day, 2, digest), DailySummary(day, 2, digest)).outcome shouldBe ReconcileOutcome.MATCH
            DayReconciliation(day, DailySummary(day, 2, digest), DailySummary(day, 3, digest)).outcome shouldBe ReconcileOutcome.DRIFT
            DayReconciliation(day, DailySummary(day, 2, digest), DailySummary(day, 2, "b".repeat(64))).outcome shouldBe
                ReconcileOutcome.DRIFT
            DayReconciliation(day, null, DailySummary(day, 2, digest)).outcome shouldBe ReconcileOutcome.UNVERIFIED
            DayReconciliation(day, DailySummary(day, 2, digest), null).outcome shouldBe ReconcileOutcome.UNVERIFIED
        }

        test("照合は前日から 38 日前まで(遅れて届いたデータを作り直す 31 日 + 1 週間)。ハッシュの行は契約の形") {
            val days = ReconcilePolicy.days(day)

            days.size shouldBe 38
            days.first() shouldBe day.minusDays(38)
            days.last() shouldBe day.minusDays(1)
            DailyOrderDigest.line("o-1", OrderStatus.SHIPPED, 1200, "JPY") shouldBe "o-1\tSHIPPED\t1200\tJPY"
        }
    })
