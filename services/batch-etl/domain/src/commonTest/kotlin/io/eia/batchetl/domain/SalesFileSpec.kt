package io.eia.batchetl.domain

import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

class SalesFileSpec :
    FunSpec({
        test("売上ファイルの名前の時刻は、業務の日の締め(日本時間の翌日 00:00 = UTC の当日 15:00)") {
            val day = (BusinessDay.parse("2026-10-14") as Result.Ok).value
            SalesFileRevision.cutoff(day) shouldBe Instant.parse("2026-10-14T15:00:00Z")
            // 月末・年末をまたぐ
            SalesFileRevision.cutoff((BusinessDay.parse("2026-12-31") as Result.Ok).value) shouldBe Instant.parse("2026-12-31T15:00:00Z")
        }
    })
