@file:Suppress("MagicNumber") // テストデータの件数・日数、乱数のバイト数

package io.eia.shipping.adapters

import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import io.eia.shipping.adapters.out.persistence.ExposedTransactionRunner
import io.eia.shipping.adapters.out.persistence.InboxProcessedCommands
import io.eia.shipping.adapters.out.persistence.JdbcShipmentStore
import io.eia.shipping.adapters.out.persistence.ShippingSchema
import io.eia.shipping.adapters.out.persistence.UuidV7ShipmentStamps
import io.eia.shipping.application.port.inbound.CommandEnvelope
import io.eia.shipping.application.port.outbound.ShippingReplies
import io.eia.shipping.application.usecase.ArrangeShipmentService
import io.eia.shipping.application.usecase.CancelShipmentService
import io.eia.shipping.application.usecase.PurgeExpiredRecordsService
import io.eia.shipping.domain.ArrangeReply
import io.eia.shipping.domain.CancelOutcome
import io.eia.shipping.domain.Destination
import io.eia.shipping.domain.RejectionReason
import io.eia.shipping.domain.SettledRetention
import io.eia.shipping.domain.ShipmentLine
import io.eia.shipping.domain.ShippingRules
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val OWNER_ROLE = "shipping_service"
private val LINES = listOf(ShipmentLine(1, "SKU-1", 1))

/** 書いた返事を記録する(Outbox への書き込みは app の統合テストで Debezium を通して確かめる)。 */
private class RecordingReplies : ShippingReplies {
    private val mutex = Mutex()
    val replies = mutableListOf<Any>()

    override suspend fun arrangeReplied(
        sagaId: String,
        orderId: String,
        reply: ArrangeReply,
    ): Result<Unit, DomainError> = mutex.withLock { ok(Unit).also { replies += reply } }

    override suspend fun cancelled(
        sagaId: String,
        orderId: String,
        outcome: CancelOutcome,
    ): Result<Unit, DomainError> = mutex.withLock { ok(Unit).also { replies += outcome } }
}

/** shipping の永続化とユースケースを、実際の PostgreSQL で確かめる(ADR-0029 §3・§5・§7)。ロールはローカル基盤と同じ構成。 */
class ShippingPersistenceIT :
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
                    "CREATE ROLE ${ShippingSchema.APP_ROLE} LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE",
                )
                statement.execute("CREATE ROLE ${ShippingSchema.CDC_ROLE} LOGIN REPLICATION PASSWORD '${randomHex()}'")
            }
        }

        class Service(
            val name: String,
        ) {
            val database = Database.connect(dataSource(ShippingSchema.APP_ROLE, appPassword, name))
            val replies = RecordingReplies()
            private val transactions = ExposedTransactionRunner(database)
            private val processed = InboxProcessedCommands(database, "shipping.command")
            private val store = JdbcShipmentStore(database)
            val arrange = ArrangeShipmentService(transactions, processed, store, replies, ShippingRules(), UuidV7ShipmentStamps())
            val cancel = CancelShipmentService(transactions, processed, store, replies, ShippingRules())
            val purge = PurgeExpiredRecordsService(store, processed, SettledRetention.DEFAULT, batchSize = 2)

            fun execute(sql: String) = superuser(name).connection.use { c -> c.createStatement().use { it.execute(sql) } }

            fun query(sql: String): String? = superuser(name).connection.use { firstValue(it, sql) }
        }

        fun newService(): Service {
            val name = "shipping_it_${databases.incrementAndGet()}"
            superuser().connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE DATABASE $name OWNER $OWNER_ROLE")
                    statement.execute("REVOKE ALL ON DATABASE $name FROM PUBLIC")
                    statement.execute("GRANT CONNECT ON DATABASE $name TO ${ShippingSchema.APP_ROLE}")
                }
            }
            val owner = dataSource(OWNER_ROLE, ownerPassword, name)
            ShippingSchema.migrate(owner).ok()
            ShippingSchema.migrate(owner).ok()
            return Service(name)
        }

        fun envelope(saga: String) = CommandEnvelope(Uuid.random().toString(), "shipping.shipment.cmd-arrange.v1", saga, "order-$saga")

        test("出荷・送り直し(同じ出荷 ID と時刻)・出荷できない国の拒否・出荷の後の取消(ALREADY_SHIPPED)を、実際の DB で反映する") {
            val service = newService()
            service.arrange(envelope("saga-1"), Destination("JP"), LINES).ok()
            service.arrange(envelope("saga-1"), Destination("JP"), LINES).ok()
            service.arrange(envelope("saga-2"), Destination("US"), LINES).ok()
            service.cancel(envelope("saga-1")).ok()

            val shipped = service.replies.replies[0].shouldBeInstanceOf<ArrangeReply.Shipped>()
            // 記録から返し直した返事は、最初の返事と同じ(時刻はマイクロ秒で、DB の精度と同じ)
            service.replies.replies shouldBe
                listOf(shipped, shipped, ArrangeReply.Rejected(RejectionReason.UNSUPPORTED_DESTINATION), CancelOutcome.ALREADY_SHIPPED)
            service.query("SELECT status FROM shipment WHERE saga_id = 'saga-1'") shouldBe "SHIPPED"
            // 届け先は国だけを記録する(データの最小化)
            service.query("SELECT destination_country FROM shipment WHERE saga_id = 'saga-1'") shouldBe "JP"
            service.query(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'shipment' AND column_name LIKE '%postal%'",
            ) shouldBe
                "0"
        }

        test("ご指示 2: 保持期間の内側で遅れて届いた手配の指示は、取消済みの印で拒否する。保持期間を過ぎた印だけを消し、出荷の記録は消さない") {
            val service = newService()
            service.cancel(envelope("saga-late")).ok()
            service.replies.replies.last() shouldBe CancelOutcome.NOT_ARRANGED
            service.execute("UPDATE shipment SET settled_at = clock_timestamp() - interval '29 days' WHERE saga_id = 'saga-late'")
            service.purge().ok().records shouldBe 0
            service.arrange(envelope("saga-late"), Destination("JP"), LINES).ok()
            service.replies.replies.last() shouldBe ArrangeReply.Rejected(RejectionReason.ALREADY_CANCELLED)
            service.query("SELECT status FROM shipment WHERE saga_id = 'saga-late'") shouldBe "CANCELLED_BEFORE_ARRANGEMENT"

            service.arrange(envelope("saga-shipped"), Destination("JP"), LINES).ok()
            service.execute("UPDATE shipment SET created_at = clock_timestamp() - interval '400 days' WHERE saga_id = 'saga-shipped'")
            service.execute("UPDATE shipment SET settled_at = clock_timestamp() - interval '31 days' WHERE saga_id = 'saga-late'")
            service.purge().ok().records shouldBe 1
            service.query("SELECT status FROM shipment WHERE saga_id = 'saga-late'") shouldBe null
            service.query("SELECT status FROM shipment WHERE saga_id = 'saga-shipped'") shouldBe "SHIPPED"
        }

        test("アプリのロールは出荷の記録を書き換えられない(UPDATE なし)。Debezium は DB に接続できる") {
            val service = newService()
            dataSource(ShippingSchema.APP_ROLE, appPassword, service.name).connection.use { connection ->
                listOf("UPDATE shipment SET status = 'REJECTED'", "TRUNCATE shipment").forEach { sql ->
                    shouldThrow<SQLException> { connection.createStatement().use { it.execute(sql) } }.sqlState shouldBe "42501"
                }
            }
            service.query("SELECT has_database_privilege('${ShippingSchema.CDC_ROLE}', current_database(), 'CONNECT')") shouldBe "t"
        }
    })

private fun firstValue(
    connection: Connection,
    sql: String,
): String? = connection.createStatement().use { s -> s.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null } }

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
