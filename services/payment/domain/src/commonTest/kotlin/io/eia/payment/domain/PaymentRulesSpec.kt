package io.eia.payment.domain

import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.days

private val RULES = PaymentRules(Amount(1_000, "JPY"))

class PaymentRulesSpec :
    FunSpec({
        test("上限以下で同じ通貨なら承認し、承認 ID を付けて記録する(上限ちょうども承認)") {
            val decision =
                RULES
                    .authorize(
                        null,
                        "saga-1",
                        "order-1",
                        Amount(1_000, "JPY"),
                    ) { "auth-1" }
                    .shouldBeInstanceOf<AuthorizeDecision.Record>()
            decision.reply shouldBe AuthorizeReply.Authorized("auth-1")
            decision.authorization shouldBe
                Authorization(
                    "saga-1",
                    "order-1",
                    AuthorizationStatus.AUTHORIZED,
                    authorizationId = "auth-1",
                    amount = Amount(1_000, "JPY"),
                )
        }

        test("上限を超える・通貨が違う金額は LIMIT_EXCEEDED で拒否を記録する(承認 ID は採らない)") {
            listOf(Amount(1_001, "JPY"), Amount(1, "USD")).forEach { amount ->
                val decision =
                    RULES.authorize(null, "saga-1", "order-1", amount) { error("採番しない") }.shouldBeInstanceOf<AuthorizeDecision.Record>()
                decision.reply shouldBe AuthorizeReply.Declined(DeclineReason.LIMIT_EXCEEDED)
                decision.authorization.status shouldBe AuthorizationStatus.DECLINED
            }
        }

        test("同じ Saga の 2 回目以降は、記録から返事を返し直す(取消済み・印は ALREADY_VOIDED)") {
            fun replay(existing: Authorization) = RULES.authorize(existing, "saga-1", "order-1", Amount(1, "JPY")) { error("採番しない") }
            replay(Authorization("saga-1", "order-1", AuthorizationStatus.AUTHORIZED, authorizationId = "auth-1")) shouldBe
                AuthorizeDecision.Replay(AuthorizeReply.Authorized("auth-1"))
            replay(Authorization("saga-1", "order-1", AuthorizationStatus.DECLINED, decline = DeclineReason.LIMIT_EXCEEDED)) shouldBe
                AuthorizeDecision.Replay(AuthorizeReply.Declined(DeclineReason.LIMIT_EXCEEDED))
            replay(Authorization("saga-1", "order-1", AuthorizationStatus.VOIDED, authorizationId = "auth-1")) shouldBe
                AuthorizeDecision.Replay(AuthorizeReply.Declined(DeclineReason.ALREADY_VOIDED))
            replay(Authorization("saga-1", "order-1", AuthorizationStatus.VOIDED_BEFORE_AUTHORIZATION)) shouldBe
                AuthorizeDecision.Replay(AuthorizeReply.Declined(DeclineReason.ALREADY_VOIDED))
        }

        test("取消: 承認していれば取り消す。記録がなければ印を作る。取消済み・拒否・印なら返し直す") {
            RULES.void(Authorization("s", "o", AuthorizationStatus.AUTHORIZED, authorizationId = "a"), "s", "o") shouldBe VoidDecision.Void
            VoidDecision.Void.outcome shouldBe VoidOutcome.VOIDED
            val marked = RULES.void(null, "s", "o").shouldBeInstanceOf<VoidDecision.MarkVoidedBeforeAuthorization>()
            marked.marker shouldBe Authorization("s", "o", AuthorizationStatus.VOIDED_BEFORE_AUTHORIZATION)
            marked.outcome shouldBe VoidOutcome.NOT_AUTHORIZED
            RULES.void(Authorization("s", "o", AuthorizationStatus.VOIDED), "s", "o") shouldBe VoidDecision.Replay(VoidOutcome.VOIDED)
            RULES.void(Authorization("s", "o", AuthorizationStatus.DECLINED), "s", "o") shouldBe
                VoidDecision.Replay(VoidOutcome.NOT_AUTHORIZED)
            RULES.void(Authorization("s", "o", AuthorizationStatus.VOIDED_BEFORE_AUTHORIZATION), "s", "o") shouldBe
                VoidDecision.Replay(VoidOutcome.NOT_AUTHORIZED)
        }

        test("終わった状態は、有効な承認(AUTHORIZED)以外") {
            AuthorizationStatus.entries.filter { !it.isSettled } shouldBe listOf(AuthorizationStatus.AUTHORIZED)
        }

        test("値の検査と既定値") {
            (Amount.of(-1, "jpy") as Result.Err).error.violations.map { it.field } shouldBe listOf("amount.minorUnits", "amount.currency")
            (Amount.of(0, "JPY") as Result.Ok).value shouldBe Amount(0, "JPY")
            (validateIdentifiers("sagaId" to "", "orderId" to "o".repeat(MAX_IDENTIFIER_LENGTH + 1)) as Result.Err).error.violations.map {
                it.field
            } shouldBe listOf("sagaId", "orderId")
            (validateIdentifiers("sagaId" to "s") is Result.Ok) shouldBe true
            PaymentRules.DEFAULT_LIMIT shouldBe Amount(1_000_000, "JPY")
            SettledRetention.DEFAULT.value shouldBe 30.days
            (SettledRetention.of(13.days) is Result.Err) shouldBe true
            (SettledRetention.of(14.days) as Result.Ok).value.value shouldBe 14.days
        }
    })
