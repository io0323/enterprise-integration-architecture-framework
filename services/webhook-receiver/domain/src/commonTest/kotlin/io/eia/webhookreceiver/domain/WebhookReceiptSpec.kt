package io.eia.webhookreceiver.domain

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val SHA_A = "a".repeat(64)
private val SHA_B = "b".repeat(64)
private val AT = Instant.parse("2026-10-21T00:00:00Z")

private fun receipt(
    id: String = "evt_1",
    sha: String = SHA_A,
): WebhookReceipt = (WebhookReceipt.of(id, "crm", sha, AT, AT) as Result.Ok).value

class WebhookReceiptSpec :
    FunSpec({
        test("記録がなければ新しい受け取り。記録済みなら再送(本文のハッシュが同じかどうか)") {
            receipt().against(null) shouldBe ReceiptOutcome.Recorded
            receipt().against(receipt()) shouldBe ReceiptOutcome.Duplicate(sameBody = true)
            receipt(sha = SHA_B).against(receipt()) shouldBe ReceiptOutcome.Duplicate(sameBody = false)
        }

        test("イベント ID・送り手・ハッシュの形を確かめる(項目ごとに理由を返す)") {
            val invalid = WebhookReceipt.of("id with space", "CRM", "XYZ", AT, AT)
            invalid
                .shouldBeInstanceOf<Result.Err<ValidationError>>()
                .error.violations
                .map { it.field } shouldBe
                listOf("webhookId", "source", "bodySha256")
            WebhookReceipt.of("x".repeat(129), "crm", SHA_A, AT, AT).shouldBeInstanceOf<Result.Err<ValidationError>>()
            WebhookReceipt
                .of(
                    "msg_p5jXN8AQM9LWM0D4loKWxJek:1.2-3",
                    "crm-saas",
                    SHA_A,
                    AT,
                    AT,
                ).shouldBeInstanceOf<Result.Ok<WebhookReceipt>>()
        }

        test("保持期間は 7 日以上(既定 30 日)") {
            ReceiptRetention.DEFAULT.value shouldBe 30.days
            (ReceiptRetention.of(7.days) as Result.Ok).value.value shouldBe 7.days
            (
                ReceiptRetention.of(
                    7.days - 1.hours,
                    "WEBHOOK_RECEIVER_RECEIPT_RETENTION",
                ) as Result.Err
            ).error.violations.single().field shouldBe
                "WEBHOOK_RECEIVER_RECEIPT_RETENTION"
        }
    })
