package io.eia.order.adapters.out.persistence

import io.eia.shared.kernel.Result
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.SQLException

class OrderSchemaIT :
    FunSpec({
        val environment = OrderDatabaseEnvironment()
        beforeSpec { environment.start() }
        afterSpec { environment.close() }

        test("order と監査の表を所有者のロールで作り、2 回目のマイグレーションは何もしない") {
            val db = environment.newDatabase()
            db.count("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success") shouldBe 1
            db.count("SELECT count(*) FROM audit.audit_schema_history WHERE version = '1' AND success") shouldBe 1
            db.count(
                "SELECT count(*) FROM pg_tables WHERE tablename IN ('orders', 'order_lines') AND tableowner = 'order_service'",
            ) shouldBe
                2
            (OrderSchema.migrate(db.owner) is Result.Ok) shouldBe true
        }

        test("アプリのロールは、付けた権限(orders の SELECT / INSERT / UPDATE、order_lines の SELECT / INSERT)だけを持つ") {
            val db = environment.newDatabase()
            val repository = ExposedOrderRepository(db.database)
            repository.insert(order()).ok()
            db.executeAsApp("UPDATE orders SET status = 'CONFIRMED' WHERE id = 'ord-1'")
            db.executeAsApp("SELECT * FROM order_lines")

            val denied =
                listOf(
                    "DELETE FROM orders",
                    "UPDATE order_lines SET quantity = 9",
                    "DELETE FROM order_lines",
                    "TRUNCATE orders",
                    "CREATE TABLE app_owned (id int)",
                    "ALTER TABLE orders ADD COLUMN x int",
                    "DROP TABLE order_lines",
                )
            denied.forEach { sql ->
                val e = shouldThrow<SQLException> { db.executeAsApp(sql) }
                // 42501: insufficient_privilege、42P... ではなく権限で拒否されること
                (e.sqlState to sql) shouldBe ("42501" to sql)
            }
        }

        test("監査の表(同じ DB のスキーマ audit)は、アプリのロールでは追記と参照だけができる") {
            val db = environment.newDatabase()
            db.executeAsApp("SELECT count(*) FROM audit.audit_log")
            listOf("DELETE FROM audit.audit_log", "TRUNCATE audit.audit_log", "UPDATE audit.audit_log SET action = 'x'").forEach { sql ->
                shouldThrow<SQLException> { db.executeAsApp(sql) }.sqlState shouldBe "42501"
            }
        }
    })
