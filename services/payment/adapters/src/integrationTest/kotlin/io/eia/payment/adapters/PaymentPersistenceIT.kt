@file:Suppress("MagicNumber") // テストデータの金額・件数・日数、乱数のバイト数

package io.eia.payment.adapters

import io.eia.payment.adapters.out.persistence.ExposedTransactionRunner
import io.eia.payment.adapters.out.persistence.InboxProcessedCommands
import io.eia.payment.adapters.out.persistence.JdbcAuthorizationStore
import io.eia.payment.adapters.out.persistence.PaymentSchema
import io.eia.payment.adapters.out.persistence.TransientSqlError
import io.eia.payment.adapters.out.persistence.UuidV7AuthorizationIds
import io.eia.payment.application.port.inbound.CommandEnvelope
import io.eia.payment.application.port.outbound.PaymentReplies
import io.eia.payment.application.usecase.AuthorizePaymentService
import io.eia.payment.application.usecase.PurgeExpiredRecordsService
import io.eia.payment.application.usecase.VoidPaymentService
import io.eia.payment.domain.Amount
import io.eia.payment.domain.AuthorizeReply
import io.eia.payment.domain.DeclineReason
import io.eia.payment.domain.PaymentRules
import io.eia.payment.domain.SettledRetention
import io.eia.payment.domain.VoidOutcome
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

private const val OWNER_ROLE = "payment_service"

/** 書いた返事を記録する(Outbox への書き込みは app の統合テストで Debezium を通して確かめる)。 */
private class RecordingReplies : PaymentReplies {
    private val mutex = Mutex()
    val replies = mutableListOf<Any>()

    override suspend fun authorizeReplied(
        sagaId: String,
        orderId: String,
        reply: AuthorizeReply,
    ): Result<Unit, DomainError> = mutex.withLock { ok(Unit).also { replies += reply } }

    override suspend fun voided(
        sagaId: String,
        orderId: String,
        outcome: VoidOutcome,
    ): Result<Unit, DomainError> = mutex.withLock { ok(Unit).also { replies += outcome } }
}

/** payment の永続化とユースケースを、実際の PostgreSQL で確かめる(ADR-0029 §5・§7)。ロールはローカル基盤と同じ構成。 */
class PaymentPersistenceIT :
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
                statement.execute("CREATE ROLE ${PaymentSchema.APP_ROLE} LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE")
                statement.execute("CREATE ROLE ${PaymentSchema.CDC_ROLE} LOGIN REPLICATION PASSWORD '${randomHex()}'")
            }
        }

        class Service(
            val name: String,
        ) {
            val database = Database.connect(dataSource(PaymentSchema.APP_ROLE, appPassword, name))
            val replies = RecordingReplies()
            private val transactions = ExposedTransactionRunner(database)
            private val processed = InboxProcessedCommands(database, "payment.command")
            private val store = JdbcAuthorizationStore(database)
            private val rules = PaymentRules(Amount(10_000, "JPY"))
            val authorize = AuthorizePaymentService(transactions, processed, store, replies, rules, UuidV7AuthorizationIds())
            val void = VoidPaymentService(transactions, processed, store, replies, rules)
            val purge = PurgeExpiredRecordsService(store, processed, SettledRetention.DEFAULT, batchSize = 2)

            fun execute(sql: String) = superuser(name).connection.use { c -> c.createStatement().use { it.execute(sql) } }

            fun query(sql: String): String? = superuser(name).connection.use { firstValue(it, sql) }
        }

        fun newService(): Service {
            val name = "payment_it_${databases.incrementAndGet()}"
            superuser().connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE DATABASE $name OWNER $OWNER_ROLE")
                    statement.execute("REVOKE ALL ON DATABASE $name FROM PUBLIC")
                    statement.execute("GRANT CONNECT ON DATABASE $name TO ${PaymentSchema.APP_ROLE}")
                }
            }
            val owner = dataSource(OWNER_ROLE, ownerPassword, name)
            PaymentSchema.migrate(owner).ok()
            PaymentSchema.migrate(owner).ok()
            return Service(name)
        }

        fun envelope(saga: String) = CommandEnvelope(Uuid.random().toString(), "payment.payment.cmd-authorize.v1", saga, "order-$saga")

        test("承認・送り直し(同じ承認 ID)・上限超えの拒否・取消を、実際の DB で 1 回ずつだけ反映する") {
            val service = newService()
            service.authorize(envelope("saga-1"), "cust-1", Amount(5_000, "JPY")).ok()
            service.authorize(envelope("saga-1"), "cust-1", Amount(5_000, "JPY")).ok()
            service.authorize(envelope("saga-2"), "cust-1", Amount(10_001, "JPY")).ok()
            service.void(envelope("saga-1")).ok()
            service.void(envelope("saga-1")).ok()

            val authorized = service.replies.replies[0] as AuthorizeReply.Authorized
            service.replies.replies shouldBe
                listOf(
                    authorized,
                    authorized,
                    AuthorizeReply.Declined(DeclineReason.LIMIT_EXCEEDED),
                    VoidOutcome.VOIDED,
                    VoidOutcome.VOIDED,
                )
            service.query("SELECT status FROM authorization_record WHERE saga_id = 'saga-1'") shouldBe "VOIDED"
            service.query("SELECT authorization_id FROM authorization_record WHERE saga_id = 'saga-1'") shouldBe authorized.authorizationId
            // 顧客 ID は記録しない(データの最小化)
            service.query("SELECT count(*) FROM information_schema.columns WHERE column_name LIKE '%customer%'") shouldBe "0"
        }

        test("同じ Saga の承認の指示が並行に届いても、承認は 1 つだけ(同時の挿入は Transient で、やり直すと返し直しになる)") {
            val service = newService()
            val first =
                coroutineScope {
                    (1..10)
                        .map {
                            async(
                                Dispatchers.IO,
                            ) { service.authorize(envelope("saga-race"), "cust-1", Amount(100, "JPY")) }
                        }.awaitAll()
                }
            first.filterIsInstance<Result.Err<DomainError>>().forEach { (it.error is TransientSqlError) shouldBe true }
            // Transient は Consumer がその場でやり直す。ここでは同じだけやり直す
            repeat(first.count { it is Result.Err }) { service.authorize(envelope("saga-race"), "cust-1", Amount(100, "JPY")).ok() }

            service.replies.replies
                .map { (it as AuthorizeReply.Authorized).authorizationId }
                .toSet()
                .size shouldBe 1
            service.query("SELECT count(*) FROM authorization_record") shouldBe "1"
        }

        test("ご指示 2: 保持期間の内側で遅れて届いた承認の指示は、取消済みの印で拒否する。保持期間を過ぎた印だけを消し、有効な承認は消さない") {
            val service = newService()
            service.void(envelope("saga-late")).ok()
            service.execute(
                "UPDATE authorization_record SET settled_at = clock_timestamp() - interval '29 days' WHERE saga_id = 'saga-late'",
            )
            service.purge().ok().records shouldBe 0
            service.authorize(envelope("saga-late"), "cust-1", Amount(100, "JPY")).ok()
            service.replies.replies.last() shouldBe AuthorizeReply.Declined(DeclineReason.ALREADY_VOIDED)
            service.query("SELECT status FROM authorization_record WHERE saga_id = 'saga-late'") shouldBe "VOIDED_BEFORE_AUTHORIZATION"

            service.authorize(envelope("saga-active"), "cust-1", Amount(100, "JPY")).ok()
            service.execute(
                "UPDATE authorization_record SET created_at = clock_timestamp() - interval '400 days' WHERE saga_id = 'saga-active'",
            )
            service.execute(
                "UPDATE authorization_record SET settled_at = clock_timestamp() - interval '31 days' WHERE saga_id = 'saga-late'",
            )
            service.purge().ok().records shouldBe 1
            service.query("SELECT status FROM authorization_record WHERE saga_id = 'saga-late'") shouldBe null
            service.query("SELECT status FROM authorization_record WHERE saga_id = 'saga-active'") shouldBe "AUTHORIZED"
        }

        test("アプリのロールは記録の中身を書き換えられない(付けた権限だけ)。Debezium は DB に接続できる") {
            val service = newService()
            dataSource(PaymentSchema.APP_ROLE, appPassword, service.name).connection.use { connection ->
                listOf(
                    "UPDATE authorization_record SET amount_minor = 0",
                    "UPDATE authorization_record SET authorization_id = 'x'",
                    "TRUNCATE authorization_record",
                ).forEach { sql ->
                    shouldThrow<SQLException> { connection.createStatement().use { it.execute(sql) } }.sqlState shouldBe "42501"
                }
            }
            service.query("SELECT has_database_privilege('${PaymentSchema.CDC_ROLE}', current_database(), 'CONNECT')") shouldBe "t"
        }
    })

private fun firstValue(
    connection: Connection,
    sql: String,
): String? = connection.createStatement().use { s -> s.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null } }

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
