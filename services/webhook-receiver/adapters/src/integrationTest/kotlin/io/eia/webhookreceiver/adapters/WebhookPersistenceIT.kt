@file:Suppress("MagicNumber") // テストデータの件数・日数、乱数のバイト数

package io.eia.webhookreceiver.adapters

import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.webhookreceiver.adapters.out.persistence.ExposedTransactionRunner
import io.eia.webhookreceiver.adapters.out.persistence.JdbcWebhookReceipts
import io.eia.webhookreceiver.adapters.out.persistence.WebhookReceiverSchema
import io.eia.webhookreceiver.application.port.inbound.ReceiveWebhook
import io.eia.webhookreceiver.application.port.outbound.IngestOutbox
import io.eia.webhookreceiver.application.usecase.PurgeReceiptsService
import io.eia.webhookreceiver.application.usecase.ReceiveWebhookService
import io.eia.webhookreceiver.domain.ReceiptOutcome
import io.eia.webhookreceiver.domain.ReceiptRetention
import io.eia.webhookreceiver.domain.WebhookReceipt
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.SecureRandom
import java.sql.SQLException
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val OWNER_ROLE = "webhook_receiver"
private val SIGNED = Instant.parse("2026-10-21T00:00:00.123456Z")

/** Outbox に書いた受け取り(Outbox の行と WAL は app の統合テストで確かめる)。[gate] があれば、書く前にそれを待つ。 */
private class RecordingOutbox : IngestOutbox {
    val written = mutableListOf<String>()
    var failure: DomainError? = null
    var gate: CompletableDeferred<Unit>? = null
    val entered = CompletableDeferred<Unit>()

    override suspend fun received(
        receipt: WebhookReceipt,
        contentType: String,
        body: ByteArray,
    ): Result<Unit, DomainError> {
        entered.complete(Unit)
        gate?.await()
        failure?.let { return err(it) }
        synchronized(written) { written += receipt.webhookId }
        return ok(Unit)
    }
}

private fun command(
    id: String,
    sha: String = "a".repeat(64),
) = ReceiveWebhook(id, "crm", SIGNED, "application/json", sha, """{"data":{"id":"cus_1"}}""".toByteArray())

/** 受け取りの記録を、実際の PostgreSQL で確かめる(ADR-0035 §3)。ロールはローカル基盤と同じ構成。 */
class WebhookPersistenceIT :
    FunSpec({
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical")
                .also { it.start() }
        afterSpec { postgres.stop() }
        val ownerPassword = randomHex()
        val appPassword = randomHex()
        val databases = AtomicInteger()

        fun dataSource(
            user: String,
            password: String,
            database: String,
        ) = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replaceAfterLast('/', database))
            this.user = user
            this.password = password
        }

        fun superuser(database: String = postgres.databaseName) = dataSource(postgres.username, postgres.password, database)

        superuser().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $OWNER_ROLE LOGIN PASSWORD '$ownerPassword'")
                statement.execute(
                    "CREATE ROLE ${WebhookReceiverSchema.APP_ROLE} LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE",
                )
                statement.execute("CREATE ROLE ${WebhookReceiverSchema.CDC_ROLE} LOGIN REPLICATION PASSWORD '${randomHex()}'")
            }
        }

        class Service(
            val name: String,
        ) {
            val database = Database.connect(dataSource(WebhookReceiverSchema.APP_ROLE, appPassword, name))
            val outbox = RecordingOutbox()
            val receipts = JdbcWebhookReceipts(database)
            val receive = ReceiveWebhookService(ExposedTransactionRunner(database), receipts, outbox)
            val purge = PurgeReceiptsService(receipts, ReceiptRetention.DEFAULT, batchSize = 2)

            fun execute(sql: String) = superuser(name).connection.use { c -> c.createStatement().use { it.execute(sql) } }

            fun strings(sql: String): List<String> =
                superuser(name).connection.use { c ->
                    c.createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
                }
        }

        fun newService(): Service {
            val name = "webhook_receiver_it_${databases.incrementAndGet()}"
            superuser().connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE DATABASE $name OWNER $OWNER_ROLE")
                    statement.execute("REVOKE ALL ON DATABASE $name FROM PUBLIC")
                    statement.execute("GRANT CONNECT ON DATABASE $name TO ${WebhookReceiverSchema.APP_ROLE}")
                }
            }
            val owner = dataSource(OWNER_ROLE, ownerPassword, name)
            WebhookReceiverSchema.migrate(owner).ok()
            // 2 回目も成功する(適用済みの版は飛ばす)
            WebhookReceiverSchema.migrate(owner).ok()
            return Service(name)
        }

        test("新しい ID は記録して Outbox に書く。同じ ID の再送は書かない。記録の列に本文はない(個人情報を置かない)") {
            val service = newService()
            service.receive(command("evt_1")).ok() shouldBe ReceiptOutcome.Recorded
            service.receive(command("evt_1")).ok() shouldBe ReceiptOutcome.Duplicate(sameBody = true)
            service.receive(command("evt_1", sha = "b".repeat(64))).ok() shouldBe ReceiptOutcome.Duplicate(sameBody = false)
            service.outbox.written shouldBe listOf("evt_1")

            service.strings(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'webhook_receipt'",
            ) shouldContainExactlyInAnyOrder
                listOf("webhook_id", "source", "body_sha256", "signed_at", "received_at")
            service.strings("SELECT to_char(signed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') FROM webhook_receipt") shouldBe
                listOf("2026-10-21T00:00:00.123456")
        }

        test("Outbox に書けなければ、受け取りの記録も残らない(送り手の再送で記録できる)") {
            val service = newService()
            service.outbox.failure = UnavailableError("outbox")
            service.receive(command("evt_1")).shouldBeInstanceOf<Result.Err<UnavailableError>>()
            service.strings("SELECT count(*) FROM webhook_receipt") shouldBe listOf("0")

            service.outbox.failure = null
            service.receive(command("evt_1")).ok() shouldBe ReceiptOutcome.Recorded
        }

        test("同じ ID の同時の受け取りは、片方だけが記録する(後の方は先の方の確定を待って、再送として扱う)") {
            val service = newService()
            val gate = CompletableDeferred<Unit>()
            service.outbox.gate = gate
            val outcomes =
                coroutineScope {
                    val first = async { service.receive(command("evt_1")) }
                    // 先の方が記録を挿入して、Outbox の前で止まっている
                    service.outbox.entered.await()
                    val second = async { service.receive(command("evt_1")) }
                    // 後の方は一意の制約で先の方の確定を待つ(先に進まない)
                    delay(500)
                    second.isCompleted shouldBe false
                    gate.complete(Unit)
                    listOf(first.await().ok(), second.await().ok())
                }
            outcomes shouldBe listOf(ReceiptOutcome.Recorded, ReceiptOutcome.Duplicate(sameBody = true))
            service.outbox.written shouldBe listOf("evt_1")
        }

        test("保持期間(30 日)を過ぎた記録だけを、上限の件数ずつ消す(DB の時計)") {
            val service = newService()
            for (n in 1..5) service.receive(command("evt_$n")).ok()
            service.execute("UPDATE webhook_receipt SET received_at = clock_timestamp() - interval '31 days' WHERE webhook_id <> 'evt_5'")
            service.purge().ok() shouldBe 4
            service.strings("SELECT webhook_id FROM webhook_receipt") shouldBe listOf("evt_5")
            // 消した後の同じ ID は、新しい受け取りとして記録する(保持期間は送り手の再送の期間より長くする。ADR-0035 §3)
            service.receive(command("evt_1")).ok() shouldBe ReceiptOutcome.Recorded
        }

        test("アプリのロールは記録を書き換えられない(SELECT / INSERT / DELETE だけ)") {
            val service = newService()
            service.receive(command("evt_1")).ok()
            dataSource(WebhookReceiverSchema.APP_ROLE, appPassword, service.name).connection.use { c ->
                shouldThrow<SQLException> {
                    c.createStatement().use { it.execute("UPDATE webhook_receipt SET source = 'other'") }
                }.sqlState shouldBe "42501"
            }
        }
    })

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
