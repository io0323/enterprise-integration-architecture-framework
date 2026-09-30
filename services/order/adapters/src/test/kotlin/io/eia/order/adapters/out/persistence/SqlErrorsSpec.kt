package io.eia.order.adapters.out.persistence

import io.eia.shared.kernel.DomainError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.SQLException

class SqlErrorsSpec :
    FunSpec({
        test("接続・ロールバック・資源不足・運用者の介入と、SQLSTATE のない例外は Retryable") {
            listOf("08006", "40001", "40P01", "53300", "57P01", null).forEach { state ->
                SqlErrors.classify(SQLException("x", state)).shouldBeInstanceOf<DomainError.Retryable>()
            }
        }

        test("そのほか(制約違反・権限の不足など)は NonRetryable") {
            listOf("23505", "42501", "22001").forEach { state ->
                SqlErrors.classify(SQLException("x", state)).shouldBeInstanceOf<DomainError.NonRetryable>()
            }
        }

        test("理由には SQLSTATE だけを入れ、ドライバのメッセージ(行の値を含みうる)は入れない") {
            val error = SqlErrors.classify(SQLException("Key (customer_id)=(secret-customer) already exists", "23505"))
            error.message shouldBe "PostgreSQL: SQLSTATE 23505"
            error.message shouldNotContain "secret"
        }
    })
