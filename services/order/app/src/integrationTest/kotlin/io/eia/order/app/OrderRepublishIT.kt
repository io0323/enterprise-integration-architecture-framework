@file:Suppress("MagicNumber") // テストの件数・時間

package io.eia.order.app

import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val BODY =
    """{"customerId":"cust-1",""" +
        """"lines":[{"productId":"prod-1","sku":"SKU-1","quantity":1,"unitPrice":{"amount":"1000","currency":"JPY"}}],""" +
        """"shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"Chiyoda","line1":"1-1"}}"""

private val ID = Regex(""""id":"([^"]+)"""")

/**
 * イベントの再発行(ADR-0046)を、実際の PostgreSQL で確かめる。Outbox に書いた内容は WAL から読む(行は残らないため)。
 * 発行(Debezium → Kafka)とヘッダは E2E で確かめる。
 */
class OrderRepublishIT :
    FunSpec({
        val environment = AppEnvironment()
        lateinit var db: String
        lateinit var server: OrderServer
        val orderIds = mutableListOf<String>()
        val from = (Clock.System.now() - 1.hours).toString()
        val to = (Clock.System.now() + 1.hours).toString()

        fun republish(vararg extra: String): Int =
            OrderCommands.run(
                listOf("republish", "--from", from, "--to", to, "--reason", "slot-lost", "--operator", "ops-1") + extra,
                environment.serveEnv(db),
            )

        fun changes(): List<String> =
            environment.superuser(db) { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT data FROM pg_logical_slot_get_changes('republish_it', NULL, NULL)").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }

        beforeSpec {
            environment.start()
            db = environment.newDatabase()
            OrderCommands.run(listOf("migrate"), environment.migrateEnv(db)) shouldBe OrderCommands.OK
            server = OrderServer.start(environment.serveEnv(db)).ok()
            environment.awaitReady(server, 30.seconds)
            repeat(3) {
                val placed =
                    environment.gateway.post(
                        "https://localhost:${server.httpsPort}/v1/orders",
                        environment.token(),
                        "republish-${UUID.randomUUID()}",
                        BODY,
                    )
                placed.statusCode() shouldBe 201
                orderIds += checkNotNull(ID.find(placed.body())).groupValues[1]
            }
            // 1 件目は PLACED のまま、2 件目は確定、3 件目は補償で取消
            environment.superuser(db) { c ->
                c.createStatement().use { s ->
                    s.execute("UPDATE orders SET status = 'CONFIRMED', version = version + 1 WHERE id = '${orderIds[1]}'")
                    s.execute("UPDATE orders SET status = 'CANCELLED', version = version + 2 WHERE id = '${orderIds[2]}'")
                    s.execute(
                        "UPDATE order_saga SET state = 'COMPENSATED', failure = 'PAYMENT_DECLINED', deadline_at = NULL " +
                            "WHERE order_id = '${orderIds[2]}'",
                    )
                    s.execute("SELECT pg_create_logical_replication_slot('republish_it', 'test_decoding')")
                }
            }
        }
        afterSpec {
            server.stop()
            environment.close()
        }

        test("dry-run は何も書かない") {
            republish() shouldBe OrderCommands.OK

            changes().filter { it.contains("republished[text]:'true'") } shouldBe emptyList()
            environment.count(db, "SELECT count(*) FROM audit.audit_log WHERE action = 'order.republish'") shouldBe 0
        }

        test("実行すると、作成の時点の内容・今の状態への遷移・取消を目印つきで Outbox に書き、監査に 1 件残す。行は残らない") {
            republish("--run-id", "run-1", "--execute") shouldBe OrderCommands.OK

            val written = changes().filter { it.startsWith("table outbox.outbox: INSERT") && it.contains("republished[text]:'true'") }
            written.count { it.contains("sales.order.created.v1") } shouldBe 3
            // PLACED のままの 1 件目は遷移を出さない
            written.count { it.contains("sales.order.status-changed.v1") } shouldBe 2
            written.count { it.contains("sales.order.cancelled.v1") } shouldBe 1
            written.forEach {
                it shouldContain "republish_reason[text]:'slot-lost'"
                it shouldContain "republish_run_id[text]:'run-1'"
            }
            environment.count(
                db,
                "SELECT count(*) FROM audit.audit_log WHERE action = 'order.republish' AND target_id = 'run-1'",
            ) shouldBe 1
            environment.count(db, "SELECT count(*) FROM outbox.outbox") shouldBe 0
        }

        test("同じ範囲でやり直せる(イベントはもう一度書く。重複は受信側が業務のキーで除く)") {
            republish("--run-id", "run-2", "--execute") shouldBe OrderCommands.OK

            changes().count { it.contains("republish_run_id[text]:'run-2'") && it.contains("sales.order.created.v1") } shouldBe 3
        }

        test("範囲の外の注文は対象にしない") {
            val before = (Clock.System.now() - 10.hours).toString()
            val alsoBefore = (Clock.System.now() - 9.hours).toString()
            OrderCommands.run(
                listOf(
                    "republish",
                    "--from",
                    before,
                    "--to",
                    alsoBefore,
                    "--reason",
                    "slot-lost",
                    "--operator",
                    "ops-1",
                    "--run-id",
                    "run-3",
                    "--execute",
                ),
                environment.serveEnv(db),
            ) shouldBe OrderCommands.OK

            changes().count { it.contains("republish_run_id[text]:'run-3'") } shouldBe 0
        }

        test("引数の誤りは使い方の終了コード") {
            OrderCommands.run(listOf("republish", "--from", "x"), environment.serveEnv(db)) shouldBe OrderCommands.USAGE
        }
    })
