package io.eia.shared.kernel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class DomainErrorSpec :
    FunSpec({
        // サービス側で定義するエラーの例: Retryable / NonRetryable のどちらか一方だけを実装する
        data class StockShortage(
            val sku: String,
        ) : DomainError.NonRetryable {
            override val code = "stock_shortage"
            override val message = "在庫が不足しています: $sku"
        }

        fun classify(error: DomainError): String =
            when (error) {
                is DomainError.Retryable -> "retry"
                is DomainError.NonRetryable -> "give-up"
            }

        test("DomainError は Retryable / NonRetryable の 2 分岐で網羅できる") {
            classify(StockShortage("A-1")) shouldBe "give-up"
            classify(UnavailableError("inventory が応答しません")) shouldBe "retry"
            classify(ValidationError.of("quantity", "1 以上です")) shouldBe "give-up"
            classify(NotFoundError("order", "o-1")) shouldBe "give-up"
            classify(ConflictError("状態が変わっています")) shouldBe "give-up"
            classify(UnexpectedError("unknown")) shouldBe "give-up"
        }

        test("標準エラーのコードとメッセージ") {
            val validation = ValidationError(listOf(FieldViolation("a", "x"), FieldViolation("b", "y")))
            validation.code shouldBe "validation_failed"
            validation.message shouldBe "a: x; b: y"
            NotFoundError("order", "o-1").message shouldBe "order 'o-1' が見つかりません"
            ConflictError("c").code shouldBe "conflict"
            UnavailableError("u", retryAfter = 5.seconds).retryAfter shouldBe 5.seconds
            UnavailableError("u").code shouldBe "unavailable"
            NotFoundError("order", "o-1").code shouldBe "not_found"
        }

        test("retryAfter の既定値は null") {
            val retryable =
                object : DomainError.Retryable {
                    override val code = "x"
                    override val message = "x"
                }
            retryable.retryAfter shouldBe null
        }

        context("catching") {
            test("成功値を Ok で返す") {
                catching { 42 } shouldBe ok(42)
            }

            test("例外を UnexpectedError に変換する") {
                val error = catching { throw IllegalStateException("db down") }.shouldBeErr()
                error.code shouldBe "unexpected"
                error.message shouldBe "IllegalStateException"
                error.cause.shouldBeInstanceOf<IllegalStateException>()
            }

            test("分類関数で Retryable に変換できる") {
                val error = catching({ UnavailableError(it.message ?: "") }) { throw IllegalStateException("timeout") }.shouldBeErr()
                error.shouldBeInstanceOf<DomainError.Retryable>()
                error.message shouldBe "timeout"
            }

            test("CancellationException は捕捉せずに再送出する") {
                shouldThrow<CancellationException> { catching { throw CancellationException("cancelled") } }
            }
        }
    })
