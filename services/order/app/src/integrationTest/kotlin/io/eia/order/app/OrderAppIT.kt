@file:Suppress("MagicNumber") // 予算・待ち時間・件数

package io.eia.order.app

import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import java.net.http.HttpResponse
import kotlin.time.TimeSource

private const val BODY =
    """
    {"customerId":"cust-1",
     "lines":[{"productId":"prod-1","sku":"SKU-1","quantity":2,"unitPrice":{"amount":"1500","currency":"JPY"}}],
     "shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"千代田区","line1":"千代田 1-1"}}
    """

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

/** migrate を実行してから serve を起動し、ready になるまで待つ(ローカル基盤と同じ順序)。 */
private fun AppEnvironment.migratedServer(
    database: String,
    extra: Map<String, String> = emptyMap(),
): OrderServer {
    OrderCommands.run(listOf("migrate"), migrateEnv(database)) shouldBe OrderCommands.OK
    return OrderServer.start(serveEnv(database, extra)).ok().also { awaitReady(it) }
}

/** ゲートウェイのクライアント証明書で、API のポート(mTLS)に注文を送る。 */
private fun AppEnvironment.place(
    server: OrderServer,
    key: String = "key-1",
): HttpResponse<String> = gateway.post("https://localhost:${server.httpsPort}/v1/orders", token(), key, BODY)

class OrderAppIT :
    FunSpec({
        val environment = AppEnvironment()
        beforeSpec { environment.start() }
        afterSpec { environment.close() }

        context("migrate と serve の資格情報の分離(ADR-0024 §2)") {
            test("migrate は所有者の資格情報で order と監査の表を作る") {
                val db = environment.newDatabase()
                OrderCommands.run(listOf("migrate"), environment.migrateEnv(db)) shouldBe OrderCommands.OK
                environment.count(
                    db,
                    "SELECT count(*) FROM pg_tables WHERE tableowner = 'order_service' AND tablename IN ('orders', 'idempotency_record')",
                ) shouldBe
                    2
                environment.count(db, "SELECT count(*) FROM pg_tables WHERE schemaname = 'audit' AND tablename = 'audit_log'") shouldBe 1
                // Outbox の表と、Debezium が読む publication(ADR-0007)
                environment.count(db, "SELECT count(*) FROM pg_tables WHERE schemaname = 'outbox' AND tablename = 'outbox'") shouldBe 1
                environment.count(db, "SELECT count(*) FROM pg_publication_tables WHERE pubname = 'eiaf_outbox'") shouldBe 1
            }

            test("serve の環境に所有者のパスワード(値でもファイルでも)があれば、起動しない") {
                val db = environment.newDatabase()
                listOf("ORDER_DB_PASSWORD" to "x", "ORDER_DB_PASSWORD_FILE" to "/run/secrets/order-db").forEach { forbidden ->
                    val refused = OrderServer.start(environment.serveEnv(db, mapOf(forbidden))).shouldBeInstanceOf<Result.Err<*>>()
                    refused.error.toString() shouldContain forbidden.first
                }
                OrderCommands.run(listOf("serve"), environment.serveEnv(db, mapOf("ORDER_DB_PASSWORD" to "x"))) shouldBe OrderCommands.USAGE
            }

            test("serve はアプリのロールで接続し、マイグレーションはしない(所有者の権限を持たない)") {
                val db = environment.newDatabase()
                val server = OrderServer.start(environment.serveEnv(db)).ok()
                try {
                    // マイグレーションしていない DB では表がなく、serve は作らない
                    environment.count(db, "SELECT count(*) FROM pg_tables WHERE tablename = 'orders'") shouldBe 0
                    environment.plain.get("http://127.0.0.1:${server.healthPort}/health/live").statusCode() shouldBe 200
                } finally {
                    server.stop()
                }
            }

            test("使い方が違えば 2") {
                OrderCommands.run(listOf("unknown"), emptyMap()) shouldBe OrderCommands.USAGE
                OrderCommands.run(listOf("migrate"), emptyMap()) shouldBe OrderCommands.USAGE
            }
        }

        context("serve") {
            test("POST・GET・再送(Idempotent-Replayed)とヘルスチェック") {
                val db = environment.newDatabase()
                val server = environment.migratedServer(db)
                try {
                    val created = environment.place(server)
                    created.statusCode() shouldBe 201
                    val location = created.header("Location") ?: error("Location がありません")

                    val replay = environment.place(server)
                    replay.header("Idempotent-Replayed") shouldBe "true"
                    replay.body() shouldBe created.body()

                    val read = environment.gateway.get("https://localhost:${server.httpsPort}/v1/$location", environment.token())
                    read.body() shouldBe created.body()
                    environment.plain.get("http://127.0.0.1:${server.healthPort}/health/ready").statusCode() shouldBe 200
                    environment.count(db, "SELECT count(*) FROM orders") shouldBe 1
                    // 受け付けは監査にも記録する(再送は記録しない。ADR-0017)
                    environment.count(db, "SELECT count(*) FROM audit.audit_log WHERE action = 'order.create'") shouldBe 1
                } finally {
                    server.stop()
                }
            }
        }

        context("リクエストの予算と DB 側の打ち切り(ADR-0024 §3)") {
            test("予算を超える遅い処理は 503 deadline-exceeded。DB の問い合わせも打ち切られ、注文も冪等の記録も残らない。同じキーで再試行すると 1 回だけ処理する") {
                val db = environment.newDatabase()
                val server = environment.migratedServer(db, mapOf("ORDER_REQUEST_BUDGET" to "2s"))
                try {
                    // 注文の INSERT で 30 秒待つ(予算 2 秒を超える遅い処理)
                    environment.superuser(db) {
                        it.createStatement().use { s ->
                            s.execute(
                                "CREATE FUNCTION slow_insert() RETURNS trigger " +
                                    "AS \$\$ BEGIN PERFORM pg_sleep(30); RETURN NEW; END \$\$ LANGUAGE plpgsql",
                            )
                            s.execute("CREATE TRIGGER slow_insert BEFORE INSERT ON orders FOR EACH ROW EXECUTE FUNCTION slow_insert()")
                        }
                    }
                    val started = TimeSource.Monotonic.markNow()
                    val timedOut = environment.place(server)

                    timedOut.statusCode() shouldBe 503
                    timedOut.header("Content-Type") shouldBe "application/problem+json"
                    timedOut.header("Retry-After") shouldBe "1"
                    val problem = timedOut.body()
                    problem shouldContain "\"type\":\"https://eiaf.example/problems/deadline-exceeded\""
                    problem shouldContain "\"correlationId\":\"${timedOut.header("X-Correlation-Id")}\""
                    // 予算(2 秒)+ DB の打ち切りの余裕(0.5 秒)の後に、pg_sleep(30) を待たずに返る
                    started.elapsedNow().inWholeMilliseconds shouldBeLessThan 6_000
                    // DB の問い合わせも打ち切られている(pg_sleep が残っていない)
                    environment.count(
                        db,
                        "SELECT count(*) FROM pg_stat_activity WHERE query LIKE 'INSERT INTO orders%' AND state = 'active'",
                    ) shouldBe
                        0
                    // 確定していない: 注文も冪等の記録(処理中は取り消した)も残らない
                    delay(500)
                    environment.count(db, "SELECT count(*) FROM orders") shouldBe 0
                    environment.count(db, "SELECT count(*) FROM idempotency_record") shouldBe 0

                    environment.superuser(db) { it.createStatement().use { s -> s.execute("DROP TRIGGER slow_insert ON orders") } }
                    val retried = environment.place(server)
                    retried.statusCode() shouldBe 201
                    retried.header("Idempotent-Replayed") shouldBe null
                    environment.count(db, "SELECT count(*) FROM orders") shouldBe 1
                } finally {
                    server.stop()
                }
            }
        }
    })
