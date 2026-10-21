@file:Suppress("MagicNumber") // HTTP のステータス・待ち時間・乱数のバイト数

package io.eia.webhookreceiver.app

import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.security.secret.Secret
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.TestPki
import io.eia.platform.webhook.StandardWebhooks
import io.eia.shared.kernel.Result
import io.eia.webhookreceiver.adapters.out.outbox.WebhookIngestSchemas
import io.eia.webhookreceiver.adapters.out.outbox.WebhookReceivedV1
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.util.HexFormat
import javax.net.ssl.SSLContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import java.net.http.HttpClient as JdkHttpClient

private const val CURRENT = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"
private const val PREVIOUS = "previous-secret-value"
private const val OWNER = "webhook_receiver"
private const val APP = "webhook_receiver_app"
private val PAYLOAD = Regex("""payload\[bytea]:'\\x([0-9a-f]+)'""")

private fun body(
    id: String,
    version: Int,
): ByteArray =
    (
        """{"type":"customer.updated", "timestamp":"2026-10-21T00:00:00Z", """ +
            """"data":{"id":"$id","display_name":"Alice","email":"alice@example.com","version":$version}}"""
    ).toByteArray()

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))

/** 受け口の時計(統合テストで進める)。 */
private class MovableClock(
    @Volatile var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/** ゲートウェイの代わりに、クライアント証明書で受け口に POST する(HTTP/1.1)。 */
private class Sender(
    context: SSLContext?,
) {
    private val client =
        JdkHttpClient
            .newBuilder()
            .version(JdkHttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .apply { context?.let(::sslContext) }
            .build()

    fun get(url: String): Int =
        client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    /** 送り手と同じ形で署名して送る([secrets] のすべてで署名する)。 */
    fun send(
        url: String,
        id: String,
        signedAt: Instant,
        body: ByteArray,
        secrets: List<String> = listOf(CURRENT),
    ): HttpResponse<String> {
        val signature = StandardWebhooks.signatureHeader(secrets.map(::Secret), id, signedAt, body)
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header(StandardWebhooks.ID, id)
                .header(StandardWebhooks.TIMESTAMP, signedAt.epochSeconds.toString())
                .header(StandardWebhooks.SIGNATURE, signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}

/**
 * webhook-receiver の全体(ADR-0035): mTLS(ゲートウェイの証明書)→ 署名の検証 → 受け取りの記録と Outbox を同じトランザクションで書く。
 * Outbox の行は書いたトランザクションの中で消える(ADR-0007)ので、PostgreSQL の WAL(test_decoding)で確かめる。
 * 発行(Debezium → Kafka)は P10 ⑥・⑦ で確かめる。
 */
class WebhookReceiverIT :
    FunSpec({
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical")
        val registry = ApicurioRegistryContainer()
        val pki = TestPki()
        val serverCert = pki.issue("webhook-receiver", listOf("webhook-receiver", "localhost"))
        val gateway = Sender(pki.issue("apisix", listOf("apisix")).clientContext(pki))
        val plain = Sender(null)
        val ownerPassword = randomHex()
        val appPassword = randomHex()
        val database = "webhook_receiver_it"
        val start = Instant.parse("2026-10-21T00:00:00Z")
        val clock = MovableClock(start)
        lateinit var server: WebhookReceiverServer

        fun superuser(
            db: String = database,
            block: (Connection) -> Unit,
        ) = PGSimpleDataSource()
            .apply {
                setURL(postgres.jdbcUrl.replaceAfterLast('/', db))
                user = postgres.username
                password = postgres.password
            }.connection
            .use(block)

        fun strings(sql: String): List<String> {
            val rows = mutableListOf<String>()
            superuser { c -> c.createStatement().use { s -> s.executeQuery(sql).use { rs -> while (rs.next()) rows += rs.getString(1) } } }
            return rows
        }

        fun env(extra: Map<String, String> = emptyMap()): Map<String, String> =
            mapOf(
                "WEBHOOK_RECEIVER_DB_URL" to postgres.jdbcUrl.replaceAfterLast('/', database),
                "WEBHOOK_RECEIVER_APP_DB_PASSWORD" to appPassword,
                "WEBHOOK_RECEIVER_HTTPS_PORT" to "0",
                "WEBHOOK_RECEIVER_HEALTH_PORT" to "0",
                WebhookReceiverConfig.SCHEMA_REGISTRY_URL to registry.baseUrl,
                WebhookReceiverConfig.TLS.cert to serverCert.certFile.toString(),
                WebhookReceiverConfig.TLS.key to serverCert.keyFile.toString(),
                WebhookReceiverConfig.TLS.clientCa to pki.caFile.toString(),
                WebhookReceiverConfig.CRM_SECRET.value to CURRENT,
                "EIA_LOG_FORMAT" to "console",
            ) + extra

        fun url() = "https://localhost:${server.httpsPort}/v1/webhooks/crm"

        fun awaitReady() {
            val deadline = TimeSource.Monotonic.markNow() + 30.seconds
            while (plain.get("http://127.0.0.1:${server.healthPort}/health/ready") != 200) {
                check(deadline.hasNotPassedNow()) { "serve が ready になりませんでした" }
                Thread.sleep(200)
            }
        }

        /** スロットを作った後の、Outbox への INSERT と、それぞれのトランザクションの行。 */
        fun changes(): List<String> {
            val rows = mutableListOf<String>()
            superuser { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT data FROM pg_logical_slot_get_changes('receiver_it', NULL, NULL)").use { rs ->
                        while (rs.next()) rows += rs.getString(1)
                    }
                }
            }
            return rows
        }

        beforeSpec {
            postgres.start()
            registry.start()
            superuser(postgres.databaseName) { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE ROLE $OWNER LOGIN PASSWORD '$ownerPassword'")
                    s.execute("CREATE ROLE $APP LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION")
                    s.execute("CREATE ROLE debezium LOGIN REPLICATION PASSWORD '${randomHex()}'")
                    s.execute("CREATE DATABASE $database OWNER $OWNER")
                    s.execute("REVOKE ALL ON DATABASE $database FROM PUBLIC")
                    s.execute("GRANT CONNECT ON DATABASE $database TO $APP")
                }
            }
            val migrate =
                mapOf(
                    "WEBHOOK_RECEIVER_DB_URL" to postgres.jdbcUrl.replaceAfterLast('/', database),
                    "WEBHOOK_RECEIVER_DB_PASSWORD" to ownerPassword,
                )
            WebhookReceiverCommands.run(listOf("migrate"), migrate) shouldBe WebhookReceiverCommands.OK
            // make schemas と同じ登録
            HttpClient(CIO).use { http ->
                val client = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), http)
                runBlocking { WebhookIngestSchemas.subjects.forEach { check(client.register(it) is Result.Ok) } }
            }
            server = (WebhookReceiverServer.start(env(), clock) as? Result.Ok)?.value ?: fail("起動できません")
            awaitReady()
        }
        afterSpec {
            server.stop()
            registry.stop()
            postgres.stop()
            pki.close()
        }
        beforeTest {
            superuser { c ->
                c.createStatement().use { it.execute("SELECT pg_create_logical_replication_slot('receiver_it', 'test_decoding')") }
            }
        }
        afterTest {
            superuser { c -> c.createStatement().use { it.execute("SELECT pg_drop_replication_slot('receiver_it')") } }
        }

        test("署名が正しければ 204。受け取りの記録と Outbox を同じトランザクションで書き、本文はバイト列のまま運ぶ(記録には本文を置かない)") {
            clock.now = start
            val body = body("cus_1", 1)
            gateway.send(url(), "evt_1", start, body).statusCode() shouldBe 204

            val changes = changes()
            val transaction = changes.dropWhile { !it.startsWith("BEGIN") }.takeWhile { !it.startsWith("COMMIT") }
            transaction.count { it.startsWith("table public.webhook_receipt: INSERT") } shouldBe 1
            val insert = transaction.single { it.startsWith("table outbox.outbox: INSERT") }
            insert shouldContain "topic[text]:'integration.crm-webhook.received.v1'"
            insert shouldContain "aggregate_type[text]:'webhook'"
            insert shouldContain "aggregate_id[text]:'evt_1'"
            insert shouldContain "ce_source[text]:'/integration/webhook-receiver'"

            val payload = HexFormat.of().parseHex(PAYLOAD.find(insert)?.groupValues?.get(1) ?: fail("payload がありません"))
            val event =
                HttpClient(CIO)
                    .use { http ->
                        val schemas =
                            WriterSchemas(ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), http))
                        runBlocking { AvroEventDeserializer(WebhookReceivedV1.serializer(), schemas).deserialize(payload) }
                    }.let { (it as? Result.Ok)?.value ?: fail("読めません: $it") }
            event.webhookId shouldBe "evt_1"
            event.source shouldBe "crm"
            event.signedAt shouldBe start
            event.receivedAt shouldBe start
            event.body.contentEquals(body) shouldBe true
            event.bodySha256 shouldBe HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))

            // 記録の行に本文(顧客の名前・メール)はない
            strings("SELECT row_to_json(r)::text FROM webhook_receipt r").single().let {
                it shouldContain "evt_1"
                it.contains("alice") shouldBe false
            }
        }

        test("正当な再送(数時間後でも、送り手が新しい時刻で署名し直す)は受け付ける。元の時刻のままの再送はリプレイとして拒否する") {
            // 送り手は 3 時間前に evt_late を送ろうとしたが、受け口に届かなかった(送り手の再送の待ち)
            val firstSignedAt = start
            clock.now = start + 3.hours
            val body = body("cus_2", 1)
            // 元の時刻の署名のまま届いたら拒否する(盗んだ Webhook の送り直しと区別できない)
            val replay = gateway.send(url(), "evt_late", firstSignedAt, body)
            replay.statusCode() shouldBe 401
            replay.body() shouldContain "webhook-timestamp-out-of-range"
            // 送り手が再送の時刻で署名し直せば受け付ける
            gateway.send(url(), "evt_late", clock.now, body).statusCode() shouldBe 204
            // 受け付けた後の再送(さらに 2 時間後、署名し直し)は 204 だが、二度は取り込まない
            clock.now = start + 5.hours
            gateway.send(url(), "evt_late", clock.now, body).statusCode() shouldBe 204

            changes().count { it.startsWith("table outbox.outbox: INSERT") } shouldBe 1
            strings("SELECT to_char(signed_at AT TIME ZONE 'UTC', 'HH24:MI') FROM webhook_receipt WHERE webhook_id = 'evt_late'") shouldBe
                listOf("03:00")
        }

        test("署名の不一致・ヘッダの欠落は拒否し、何も書かない") {
            clock.now = start
            val wrong = gateway.send(url(), "evt_bad", start, body("cus_3", 1), secrets = listOf("not-the-secret"))
            wrong.statusCode() shouldBe 401
            wrong.body() shouldContain "webhook-signature-invalid"
            changes().none { it.startsWith("table") } shouldBe true
            strings("SELECT count(*) FROM webhook_receipt WHERE webhook_id = 'evt_bad'") shouldBe listOf("0")
        }

        test("鍵の差し替え: 差し替えの間は差し替え前の鍵の署名も受け付ける") {
            clock.now = start
            val rotating =
                (
                    WebhookReceiverServer.start(
                        env(mapOf("WEBHOOK_RECEIVER_CRM_SECRET_PREVIOUS" to PREVIOUS)),
                        clock,
                    ) as Result.Ok
                ).value
            try {
                val rotatingUrl = "https://localhost:${rotating.httpsPort}/v1/webhooks/crm"
                gateway.send(rotatingUrl, "evt_old_key", start, body("cus_4", 1), secrets = listOf(PREVIOUS)).statusCode() shouldBe 204
                gateway.send(url(), "evt_old_key_2", start, body("cus_4", 2), secrets = listOf(PREVIOUS)).statusCode() shouldBe 401
            } finally {
                rotating.stop()
            }
        }

        test("受け口は mTLS だけで受ける(クライアント証明書のない接続は拒否する)") {
            shouldThrow<IOException> { Sender(pki.anonymousClientContext()).send(url(), "evt_x", start, body("cus_5", 1)) }
            plain.get("http://127.0.0.1:${server.healthPort}/health/live") shouldBe 200
        }
    })
