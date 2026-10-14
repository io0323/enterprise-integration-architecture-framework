package io.eia.batchetl.domain

import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

class BatchEtlDomainSpec :
    FunSpec({
        val address = ShippingAddress("JP", "100-0001", "東京都", "千代田区", "千代田1-1", "皇居外苑ビル 3F")

        test("業務の日は日本時間で区切る(UTC 15:00 が日の境目)") {
            BusinessDay.of(Instant.parse("2026-10-13T14:59:59Z")).toString() shouldBe "2026-10-13"
            BusinessDay.of(Instant.parse("2026-10-13T15:00:00Z")).toString() shouldBe "2026-10-14"
            BusinessDay
                .parse("2026-10-13")
                .ok()
                .minusDays(13)
                .toString() shouldBe "2026-09-30"
            BusinessDay.parse("2026-02-30").shouldBeInstanceOf<Result.Err<*>>()
            BusinessDay.parse("20261013").shouldBeInstanceOf<Result.Err<*>>()
        }

        test("取り込む前に、住所は国・都道府県・郵便番号の先頭 3 桁だけにする(市区町村・番地・建物は落とす)") {
            ShippingArea.of(address) shouldBe ShippingArea("JP", "東京都", "100")
            ShippingArea.of(address.copy(postalCode = "1A", region = " ")) shouldBe ShippingArea("JP", null, null)
            address.toString() shouldNotContain "千代田"
        }

        test("イベントを取り込む形に変える。受け付けの業務の日を決め、住所を含めない") {
            val placed =
                OrderEvent.Placed("o-1", "c-1", Instant.parse("2026-10-13T15:30:00Z"), 1200, "JPY", 2, address).toRecord()

            placed shouldBe
                OrderRecord.Placed(
                    "o-1",
                    "c-1",
                    Instant.parse("2026-10-13T15:30:00Z"),
                    BusinessDay.parse("2026-10-14").ok(),
                    1200,
                    "JPY",
                    2,
                    ShippingArea("JP", "東京都", "100"),
                )
            placed.toString() shouldNotContain "千代田"
            val changedAt = Instant.parse("2026-10-14T00:00:00Z")
            OrderEvent.StatusChanged("o-1", OrderStatus.SHIPPED, 2, changedAt).toRecord() shouldBe
                OrderRecord.StatusChanged("o-1", OrderStatus.SHIPPED, 2, changedAt)
            OrderEvent.Cancelled("o-1", changedAt, "customer").toRecord() shouldBe OrderRecord.Cancelled("o-1", changedAt, "customer")
        }

        test("遅れて届いたデータ: 実行の日と、31 日前までの変わった日を古い順に作り直す。それより古い日は作り直さない。先の日は任せる") {
            val run = BusinessDay.parse("2026-10-13").ok()
            val dirty =
                listOf(run.minusDays(1), run.minusDays(31), run.minusDays(32), run.minusDays(1), BusinessDay.parse("2026-10-14").ok())

            val plan = LateDataPolicy.plan(run, dirty)

            plan.rebuild shouldBe listOf(run.minusDays(31), run.minusDays(1), run)
            plan.tooOld shouldBe listOf(run.minusDays(32))
            LateDataPolicy.plan(run, emptyList()).rebuild shouldBe listOf(run)
        }
    })
