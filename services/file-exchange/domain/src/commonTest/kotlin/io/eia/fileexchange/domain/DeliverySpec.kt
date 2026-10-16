package io.eia.fileexchange.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DeliverySpec :
    FunSpec({
        test("取引先の ID は英小文字・数字・ハイフンだけ(メトリクスの属性と Resilience の名前に使う)") {
            PartnerId("partner01").toString() shouldBe "partner01"
            PartnerId("acme-jp").value shouldBe "acme-jp"
            listOf("", "Partner01", "-a", "a_b", "a".repeat(33), "a/b").forEach { bad ->
                shouldThrow<IllegalArgumentException> { PartnerId(bad) }
            }
        }

        test("相手の側の正式名は、データファイルの名前に .pgp を付けたもの") {
            val remote = Delivery.remoteName("sales_daily_20261014150000_001.parquet")
            remote shouldBe "sales_daily_20261014150000_001.parquet.pgp"
            val delivery = Delivery("sales_daily_20261014150000_001", PartnerId("partner01"), remote, DeliveryState.SENDING, false)
            delivery.copy(state = DeliveryState.SENT, alreadyPresent = true).alreadyPresent shouldBe true
        }

        test("配送の結果のコード(メトリクスの属性)") {
            DeliveryOutcome.entries.map { it.code } shouldBe listOf("sent", "already_present", "already_sent")
        }

        test("データセットのキーは、ファイルの名前から時刻と連番を除いたもの。形が違えば null") {
            DatasetKey.of("sales_daily_20261014150000_001") shouldBe "sales_daily"
            DatasetKey.of("erp_order_lines_20261014150000_123456") shouldBe "erp_order_lines"
            DatasetKey.of("sales_20261014150000_001") shouldBe null
            DatasetKey.of("sales_daily_2026101415_001") shouldBe null
            DatasetKey.of("sales_daily_20261014150000_01") shouldBe null
            DatasetKey.of("Sales_daily_20261014150000_001") shouldBe null
        }
    })
