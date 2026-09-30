package io.eia.shared.resilience

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RetryBudgetSpec :
    FunSpec({
        test("残高が半分以下になるとリトライを止め、成功で tokenRatio ずつ戻る") {
            val budget = RetryBudget(RetryBudgetConfig(maxTokens = 10, tokenRatio = 0.5))
            repeat(4) { budget.record(Outcome.FAILURE) }
            budget.allowsRetry() shouldBe true // 残高 6 > 5
            budget.record(Outcome.FAILURE)
            budget.allowsRetry() shouldBe false // 残高 5
            budget.record(Outcome.SUCCESS)
            budget.allowsRetry() shouldBe true // 残高 5.5
        }

        test("数えない結果では残高は変わらず、残高は 0 と maxTokens の間に収まる") {
            val budget = RetryBudget(RetryBudgetConfig(maxTokens = 2, tokenRatio = 1.0))
            repeat(10) { budget.record(Outcome.SUCCESS) }
            budget.record(Outcome.FAILURE)
            budget.allowsRetry() shouldBe false // 上限 2 から 1 減って 1(半分以下)
            repeat(10) { budget.record(Outcome.FAILURE) }
            repeat(10) { budget.record(Outcome.IGNORED) }
            budget.record(Outcome.SUCCESS)
            budget.record(Outcome.SUCCESS)
            budget.allowsRetry() shouldBe true // 0 で止まっていたので 2 に戻る
        }

        test("残高を小数で読める") {
            val budget = RetryBudget(RetryBudgetConfig(maxTokens = 10, tokenRatio = 0.25))
            budget.remaining shouldBe 10.0
            repeat(3) { budget.record(Outcome.FAILURE) }
            budget.record(Outcome.SUCCESS)
            budget.remaining shouldBe 7.25
        }

        test("不正な設定は拒否する") {
            shouldThrow<IllegalArgumentException> { RetryBudgetConfig(maxTokens = 0) }
            shouldThrow<IllegalArgumentException> { RetryBudgetConfig(tokenRatio = 0.0) }
            shouldThrow<IllegalArgumentException> { RetryBudgetConfig(tokenRatio = 1.5) }
        }
    })
