package io.eia.order.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

private val BASE =
    listOf("--from", "2026-11-01T00:00:00Z", "--to", "2026-11-02T00:00:00Z", "--reason", "slot-lost", "--operator", "ops-1")

class OrderRepublishCommandSpec :
    FunSpec({
        test("既定は dry-run。--execute で実行する。実行の ID がなければ採番する") {
            val options = OrderRepublishCommand.parse(BASE).shouldNotBeNull()
            options.execute shouldBe false
            options.from shouldBe Instant.parse("2026-11-01T00:00:00Z")
            options.reason shouldBe "slot-lost"
            options.runId.isNotBlank() shouldBe true
            OrderRepublishCommand.parse(BASE + "--execute")?.execute shouldBe true
            OrderRepublishCommand.parse(BASE + listOf("--run-id", "run-1"))?.runId shouldBe "run-1"
        }

        test("必須の引数がない・時刻が読めない・知らない引数は誤り") {
            OrderRepublishCommand.parse(BASE.drop(2)).shouldBeNull()
            OrderRepublishCommand.parse(BASE.dropLast(2)).shouldBeNull()
            OrderRepublishCommand.parse(listOf("--from", "yesterday") + BASE.drop(2)).shouldBeNull()
            OrderRepublishCommand.parse(BASE + "--force").shouldBeNull()
            OrderRepublishCommand.parse(BASE + "--run-id").shouldBeNull()
        }

        test("理由・運用者・実行の ID は決まった文字だけ(ヘッダと監査に入るので、業務のデータを入れさせない)") {
            OrderRepublishCommand.parse(BASE.dropLast(4) + listOf("--reason", "田中さんの注文", "--operator", "ops-1")).shouldBeNull()
            OrderRepublishCommand.parse(BASE.dropLast(2) + listOf("--operator", "a b")).shouldBeNull()
            OrderRepublishCommand.parse(BASE + listOf("--run-id", "a\nb")).shouldBeNull()
        }
    })
