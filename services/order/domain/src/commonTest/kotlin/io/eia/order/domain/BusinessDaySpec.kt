package io.eia.order.domain

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private fun day(text: String): BusinessDay =
    when (val parsed = BusinessDay.parse(text)) {
        is Result.Ok -> parsed.value
        is Result.Err -> fail("読めません: ${parsed.error}")
    }

class BusinessDaySpec :
    FunSpec({
        test("1 日は日本時間の 00:00 から翌日の 00:00 まで(UTC では前の日の 15:00 から)") {
            val day = day("2026-10-14")
            day.start shouldBe Instant.parse("2026-10-13T15:00:00Z")
            day.end shouldBe Instant.parse("2026-10-14T15:00:00Z")
            day.toString() shouldBe "2026-10-14"
        }

        test("時刻が属する日: 日本時間の 0 時の前後で日が変わる(UTC の日付とは違う)") {
            BusinessDay.of(Instant.parse("2026-10-13T14:59:59.999999Z")) shouldBe day("2026-10-13")
            BusinessDay.of(Instant.parse("2026-10-13T15:00:00Z")) shouldBe day("2026-10-14")
            BusinessDay.of(Instant.parse("2026-10-14T00:30:00Z")) shouldBe day("2026-10-14")
        }

        test("月末・年末・うるう日も、翌日の始まりで区切る") {
            day("2026-12-31").end shouldBe day("2027-01-01").start
            day("2028-02-29").end shouldBe Instant.parse("2028-02-29T15:00:00Z")
        }

        test("形式が違う・存在しない日付は拒否する") {
            listOf("2026-02-30", "2026-13-01", "2026-1-01", "20261014", "2026-10-14T00:00", "").forEach { text ->
                BusinessDay.parse(text).shouldBeInstanceOf<Result.Err<ValidationError>>()
            }
        }

        test("集計のハッシュの 1 行はタブ区切り(注文 ID・状態・税抜の合計の最小単位・通貨)") {
            DailyOrderDigest.line("ord-1", OrderStatus.SHIPPED, 3_000, "JPY") shouldBe "ord-1\tSHIPPED\t3000\tJPY"
        }
    })
