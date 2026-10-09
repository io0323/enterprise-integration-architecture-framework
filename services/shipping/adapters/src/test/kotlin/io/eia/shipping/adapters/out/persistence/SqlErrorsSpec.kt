package io.eia.shipping.adapters.out.persistence

import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.SQLException

class SqlErrorsSpec :
    FunSpec({
        test("直列化の失敗・デッドロック・一意制約(同時の挿入)は Transient、接続・資源・介入は Unavailable、ほかは Unexpected") {
            listOf("40001", "40P01", "23505").forEach { SqlErrors.classify(SQLException("x", it)).shouldBeInstanceOf<TransientSqlError>() }
            listOf("08006", "53300", "57P01").forEach { SqlErrors.classify(SQLException("x", it)).shouldBeInstanceOf<UnavailableError>() }
            SqlErrors.classify(SQLException("no state")).shouldBeInstanceOf<UnavailableError>()
            SqlErrors.classify(SQLException("x", "23514")).shouldBeInstanceOf<UnexpectedError>()
        }

        test("理由には SQLSTATE だけを入れる(ドライバのメッセージの行の値を入れない)") {
            val error = SqlErrors.classify(SQLException("Key (sku)=(secret) already exists", "23505"))
            error.message shouldNotContain "secret"
            error.message shouldBe "一時的に処理できません(PostgreSQL: SQLSTATE 23505)"
            error.code shouldBe "transient_sql_error"
        }
    })
