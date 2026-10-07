@file:Suppress("MagicNumber") // 待ち時間・件数

package io.eia.order.app

import io.eia.order.adapters.out.outbox.OrderCreatedV1
import io.eia.order.adapters.out.outbox.OrderStatusV1
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.util.HexFormat
import kotlin.time.Duration.Companion.seconds

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

/** test_decoding の出力を、BEGIN から COMMIT までのトランザクションごとに分ける。 */
private fun transactions(changes: List<String>): List<List<String>> =
    buildList {
        var current = mutableListOf<String>()
        changes.forEach { line ->
            when {
                line.startsWith("BEGIN") -> current = mutableListOf()
                line.startsWith("COMMIT") -> add(current)
                else -> current += line
            }
        }
    }

private val PAYLOAD = Regex("""payload\[bytea]:'\\x([0-9a-f]+)'""")

/**
 * 注文の作成と同じトランザクションで Outbox に書くこと(ADR-0007)と、スキーマ ID の事前の解決(ADR-0025 §3)を、実際の PostgreSQL と
 * Schema Registry(Apicurio)で確かめる。発行(Debezium → Kafka)は P06 ③b で確かめる。
 *
 * 契約のスキーマを登録しない状態から始める(`make schemas` の前に起動した場合と同じ)。
 */
class OrderEventOutboxIT :
    FunSpec({
        val environment = AppEnvironment()
        lateinit var db: String
        lateinit var server: OrderServer
        beforeSpec {
            environment.start(registerSchemas = false)
            db = environment.newDatabase()
        }
        afterSpec {
            server.stop()
            environment.close()
        }

        fun health() = environment.plain.get("http://127.0.0.1:${server.healthPort}/health/ready").statusCode()

        fun place(key: String) = environment.gateway.post("https://localhost:${server.httpsPort}/v1/orders", environment.token(), key, BODY)

        /** WAL を論理的に読むスロット(test_decoding)。作った時点より後の変更だけが読める。 */
        fun createSlot() =
            environment.superuser(db) { c ->
                c.createStatement().use {
                    it.execute("SELECT pg_create_logical_replication_slot('order_it', 'test_decoding')")
                }
            }

        fun changes(): List<String> =
            environment.superuser(db) { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT data FROM pg_logical_slot_get_changes('order_it', NULL, NULL)").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }

        test("スキーマ ID を解決するまで /health/ready は 503。その間の注文は 503 で、注文も監査も残らない。登録すれば ready になる") {
            OrderCommands.run(listOf("migrate"), environment.migrateEnv(db)) shouldBe OrderCommands.OK
            server = OrderServer.start(environment.serveEnv(db)).ok()

            health() shouldBe 503
            val refused = place("key-unresolved")
            refused.statusCode() shouldBe 503
            refused.header("Content-Type") shouldBe "application/problem+json"
            environment.count(db, "SELECT count(*) FROM orders") shouldBe 0
            environment.count(db, "SELECT count(*) FROM audit.audit_log") shouldBe 0

            // make schemas と同じ登録をすると、解決の繰り返し(5 秒ごと)で ready になる
            environment.registerSchemas()
            environment.awaitReady(server, 30.seconds)
        }

        test("解決した後は、Apicurio を止めても注文を作れる。Outbox に行は残らず、WAL に OrderCreated の INSERT が同じトランザクションで残る") {
            createSlot()
            val registry = environment.registry
            registry.dockerClient.pauseContainerCmd(registry.containerId).exec()
            val created =
                try {
                    place("key-1").also { it.statusCode() shouldBe 201 }
                } finally {
                    registry.dockerClient.unpauseContainerCmd(registry.containerId).exec()
                }
            val orderId = Regex(""""id":"([^"]+)"""").find(created.body())?.groupValues?.get(1) ?: error("注文 ID がありません")

            environment.count(db, "SELECT count(*) FROM outbox.outbox") shouldBe 0
            val changes = changes()
            val inserts = changes.filter { it.startsWith("table outbox.outbox: INSERT") }
            inserts.size shouldBe 1
            val insert = inserts.single()
            insert shouldContain "topic[text]:'sales.order.created.v1'"
            insert shouldContain "aggregate_type[text]:'order'"
            insert shouldContain "aggregate_id[text]:'$orderId'"
            insert shouldContain "event_type[text]:'sales.order.created'"
            insert shouldContain "ce_source[text]:'/sales/order-service'"
            insert shouldContain "correlation_id[text]:'${created.header("X-Correlation-Id")}'"
            // 注文・監査・Outbox が 1 つのトランザクション(冪等のリースは、ADR-0022 §3 のとおり別のトランザクションで先に確定する)
            val transaction = transactions(changes).single { tx -> tx.any { it.startsWith("table outbox.outbox: INSERT") } }
            transaction.count { it.startsWith("table public.orders: INSERT") } shouldBe 1
            transaction.count { it.startsWith("table audit.audit_log: INSERT") } shouldBe 1

            // ペイロードは契約のスキーマで読める(スキーマ ID 付きの Avro。ADR-0025 §1)
            val payload = HexFormat.of().parseHex(PAYLOAD.find(insert)?.groupValues?.get(1) ?: error("payload がありません"))
            val event =
                HttpClient(CIO).use { http ->
                    val schemas =
                        WriterSchemas(ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), http))
                    AvroEventDeserializer(OrderCreatedV1.serializer(), schemas).deserialize(payload).ok()
                }
            event.order.id shouldBe orderId
            event.order.customerId shouldBe "cust-1"
            event.order.status shouldBe OrderStatusV1.PLACED
            event.order.lines
                .single()
                .quantity shouldBe 2
            event.order.totalAmount.minorUnits shouldBe 3_000
            event.order.totalAmount.currency shouldBe "JPY"
            event.order.shippingAddress.countryCode shouldBe "JP"
        }

        test("同じ Idempotency-Key の再送は、イベントを書かない(保存した応答を返すだけ)") {
            createSlot()
            place("key-replay").statusCode() shouldBe 201
            place("key-replay").header("Idempotent-Replayed") shouldBe "true"

            changes().count { it.startsWith("table outbox.outbox: INSERT") } shouldBe 1
        }

        afterTest {
            environment.superuser(db) { c ->
                c.createStatement().use {
                    it.execute(
                        "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = 'order_it'",
                    )
                }
            }
        }
    })
