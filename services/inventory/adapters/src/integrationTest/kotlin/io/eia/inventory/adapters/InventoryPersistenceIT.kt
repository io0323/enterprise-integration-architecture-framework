@file:Suppress("MagicNumber") // テストデータの数量・件数・日数、乱数のバイト数

package io.eia.inventory.adapters

import io.eia.inventory.adapters.out.persistence.ExposedTransactionRunner
import io.eia.inventory.adapters.out.persistence.InboxProcessedCommands
import io.eia.inventory.adapters.out.persistence.InventorySchema
import io.eia.inventory.adapters.out.persistence.JdbcReservationStore
import io.eia.inventory.adapters.out.persistence.JdbcStockLedger
import io.eia.inventory.application.port.inbound.CommandEnvelope
import io.eia.inventory.application.port.inbound.CommandOutcome
import io.eia.inventory.application.port.outbound.InventoryReplies
import io.eia.inventory.application.usecase.PurgeExpiredRecordsService
import io.eia.inventory.application.usecase.ReleaseStockService
import io.eia.inventory.application.usecase.ReserveStockService
import io.eia.inventory.domain.RejectionReason
import io.eia.inventory.domain.ReleaseOutcome
import io.eia.inventory.domain.ReserveReply
import io.eia.inventory.domain.SettledRetention
import io.eia.inventory.domain.StockLine
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
import java.sql.SQLException
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val OWNER_ROLE = "inventory_service"
private const val GROUP = "inventory.command"

/** 書いた返事を記録する(Outbox への書き込みは app の統合テストで Debezium を通して確かめる)。 */
private class RecordingReplies : InventoryReplies {
    private val mutex = Mutex()
    val replies = mutableListOf<Pair<String, Any>>()

    override suspend fun reserveReplied(
        sagaId: String,
        orderId: String,
        reply: ReserveReply,
    ): Result<Unit, DomainError> = mutex.withLock { ok(Unit).also { replies += sagaId to reply } }

    override suspend fun released(
        sagaId: String,
        orderId: String,
        outcome: ReleaseOutcome,
    ): Result<Unit, DomainError> = mutex.withLock { ok(Unit).also { replies += sagaId to outcome } }
}

/**
 * inventory の永続化とユースケースを、実際の PostgreSQL(images.env の版)で確かめる(ADR-0029 §5・§7)。
 * ロールはローカル基盤と同じ構成(所有者・アプリ用・Debezium)。テストごとに DB を作り、所有者でマイグレーションする(在庫の初期データを含む)。
 */
class InventoryPersistenceIT :
    FunSpec({
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_connections=200")
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
                    "CREATE ROLE ${InventorySchema.APP_ROLE} LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE",
                )
                statement.execute("CREATE ROLE ${InventorySchema.CDC_ROLE} LOGIN REPLICATION PASSWORD '${randomHex()}'")
            }
        }

        class Service(
            val name: String,
        ) {
            val database = Database.connect(dataSource(InventorySchema.APP_ROLE, appPassword, name))
            val replies = RecordingReplies()
            private val transactions = ExposedTransactionRunner(database)
            private val processed = InboxProcessedCommands(database, GROUP)
            val reservations = JdbcReservationStore(database)
            private val stock = JdbcStockLedger(database)
            val reserve = ReserveStockService(transactions, processed, reservations, stock, replies)
            val release = ReleaseStockService(transactions, processed, reservations, stock, replies)
            val purge = PurgeExpiredRecordsService(reservations, processed, SettledRetention.DEFAULT, batchSize = 2)

            fun reserved(sku: String): Long = query("SELECT reserved FROM stock WHERE sku = '$sku'").toLong()

            fun status(saga: String): String? = queryOrNull("SELECT status FROM reservation WHERE saga_id = '$saga'")

            fun execute(sql: String) = superuser(name).connection.use { c -> c.createStatement().use { it.execute(sql) } }

            fun query(sql: String): String = checkNotNull(queryOrNull(sql)) { "行がありません: $sql" }

            fun queryOrNull(sql: String): String? =
                superuser(name).connection.use { c ->
                    c.createStatement().use { s -> s.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null } }
                }
        }

        /** inventory の DB を作り、所有者でマイグレーションする(2 回目は何もしない)。 */
        fun newService(): Service {
            val name = "inventory_it_${databases.incrementAndGet()}"
            superuser().connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE DATABASE $name OWNER $OWNER_ROLE")
                    statement.execute("REVOKE ALL ON DATABASE $name FROM PUBLIC")
                    statement.execute("GRANT CONNECT ON DATABASE $name TO ${InventorySchema.APP_ROLE}")
                }
            }
            val owner = dataSource(OWNER_ROLE, ownerPassword, name)
            InventorySchema.migrate(owner).ok()
            InventorySchema.migrate(owner).ok()
            return Service(name)
        }

        fun envelope(
            saga: String,
            id: String = Uuid.random().toString(),
        ) = CommandEnvelope(id, "inventory.stock.cmd-reserve.v1", saga, "order-$saga")

        fun lines(vararg quantities: Pair<String, Long>) =
            quantities.mapIndexed { index, (sku, quantity) -> StockLine.of(index + 1, sku, quantity).ok() }

        test("引当・同じ ce_id の重複・同じ Saga の送り直し・解放を、実際の DB で 1 回ずつだけ反映する") {
            val service = newService()
            val id = Uuid.random().toString()
            service.reserve(envelope("saga-1", id), lines("SKU-LIMITED" to 4L)).ok() shouldBe CommandOutcome.PROCESSED
            service.reserve(envelope("saga-1", id), lines("SKU-LIMITED" to 4L)).ok() shouldBe CommandOutcome.DUPLICATE
            service.reserve(envelope("saga-1"), lines("SKU-LIMITED" to 4L)).ok() shouldBe CommandOutcome.PROCESSED
            service.reserved("SKU-LIMITED") shouldBe 4

            service.release(envelope("saga-1")).ok()
            service.release(envelope("saga-1")).ok()
            service.reserved("SKU-LIMITED") shouldBe 0
            service.status("saga-1") shouldBe "RELEASED"
            service.replies.replies.map { it.second } shouldBe
                listOf(ReserveReply.Reserved, ReserveReply.Reserved, ReleaseOutcome.RELEASED, ReleaseOutcome.RELEASED)
            // 明細が残っている(解放で戻す数の根拠)
            service.query("SELECT count(*) FROM reservation_line WHERE saga_id = 'saga-1'") shouldBe "1"
        }

        test("ご指示 4: 同時の注文で在庫が負にならない(在庫 10 に 1 個ずつ 30 件を並行に引き当てると、ちょうど 10 件だけ成功する)") {
            val service = newService()
            val results =
                coroutineScope {
                    (1..30)
                        .map { n -> async(Dispatchers.IO) { service.reserve(envelope("saga-$n"), lines("SKU-LIMITED" to 1L)) } }
                        .awaitAll()
                }
            results.all { it is Result.Ok } shouldBe true
            val outcomes = service.replies.replies.map { it.second }
            outcomes.count { it == ReserveReply.Reserved } shouldBe 10
            outcomes.count { it == ReserveReply.Rejected(RejectionReason.INSUFFICIENT_STOCK) } shouldBe 20
            service.reserved("SKU-LIMITED") shouldBe 10
            service.query("SELECT count(*) FROM reservation WHERE status = 'RESERVED'") shouldBe "10"
            service.query("SELECT min(on_hand - reserved) FROM stock") shouldBe "0"
        }

        test("複数の SKU を逆の順に持つ注文が並行に来ても、SKU の順にロックするのでデッドロックせず、在庫は負にならない") {
            val service = newService()
            service.execute("INSERT INTO stock (sku, on_hand) VALUES ('SKU-A', 10), ('SKU-B', 10)")
            val results =
                coroutineScope {
                    (1..30)
                        .map { n ->
                            val order = if (n % 2 == 0) lines("SKU-A" to 1L, "SKU-B" to 1L) else lines("SKU-B" to 1L, "SKU-A" to 1L)
                            async(Dispatchers.IO) { service.reserve(envelope("saga-$n"), order) }
                        }.awaitAll()
                }
            // デッドロック(SQLSTATE 40P01)は起きない
            results.filterIsInstance<Result.Err<DomainError>>() shouldBe emptyList()
            service.replies.replies.count { it.second == ReserveReply.Reserved } shouldBe 10
            service.reserved("SKU-A") shouldBe 10
            service.reserved("SKU-B") shouldBe 10
        }

        test("DB の制約でも、引当の数が在庫を超えない・負にならない(アプリの判定をすり抜けても守る)") {
            val service = newService()
            listOf(
                "UPDATE stock SET reserved = on_hand + 1 WHERE sku = 'SKU-LIMITED'",
                "UPDATE stock SET reserved = -1 WHERE sku = 'SKU-LIMITED'",
            ).forEach { sql -> shouldThrow<SQLException> { service.execute(sql) }.sqlState shouldBe "23514" }
        }

        test("ご指示 2: 保持期間の内側で遅れて届いた引当の指示は、取消済みの印で拒否する。保持期間を過ぎた印だけを消す") {
            val service = newService()
            // 期限切れの補償で解放が先に届く → 印
            service.release(envelope("saga-late")).ok()
            service.status("saga-late") shouldBe "RELEASED_BEFORE_RESERVATION"

            // 29 日前に印を作ったことにする(保持期間 30 日の内側。DLQ の Replay で遅れて届いた元の指示を想定)
            service.execute("UPDATE reservation SET settled_at = clock_timestamp() - interval '29 days' WHERE saga_id = 'saga-late'")
            service.purge().ok().reservations shouldBe 0
            service.reserve(envelope("saga-late"), lines("SKU-LIMITED" to 3L)).ok() shouldBe CommandOutcome.PROCESSED
            service.replies.replies
                .last()
                .second shouldBe ReserveReply.Rejected(RejectionReason.ALREADY_RELEASED)
            service.reserved("SKU-LIMITED") shouldBe 0

            // 保持期間を過ぎた印は消える(有効な引当は、古くても消さない)
            service.reserve(envelope("saga-active"), lines("SKU-LIMITED" to 1L)).ok()
            service.execute("UPDATE reservation SET created_at = clock_timestamp() - interval '400 days' WHERE saga_id = 'saga-active'")
            service.execute("UPDATE reservation SET settled_at = clock_timestamp() - interval '31 days' WHERE saga_id = 'saga-late'")
            service.purge().ok().reservations shouldBe 1
            service.status("saga-late") shouldBe null
            service.status("saga-active") shouldBe "RESERVED"
        }

        test("保持期間の削除は、上限の件数ずつ全部を消す。冪等消費の記録も 14 日を過ぎたものだけ消す") {
            val service = newService()
            (1..5).forEach { n -> service.release(envelope("saga-$n")).ok() }
            service.execute("UPDATE reservation SET settled_at = clock_timestamp() - interval '40 days'")
            service.execute("UPDATE inbox.processed_message SET processed_at = clock_timestamp() - interval '15 days'")
            service.release(envelope("saga-new")).ok()

            service.purge().ok().let {
                it.reservations shouldBe 5
                it.processedMessages shouldBe 5
            }
            service.query("SELECT count(*) FROM reservation") shouldBe "1"
            service.query("SELECT count(*) FROM inbox.processed_message") shouldBe "1"
        }

        test("アプリのロールは在庫の数・記録の中身を書き換えられない(付けた権限だけ)") {
            val service = newService()
            dataSource(InventorySchema.APP_ROLE, appPassword, service.name).connection.use { connection ->
                listOf(
                    "UPDATE stock SET on_hand = 999",
                    "TRUNCATE reservation",
                    "UPDATE reservation SET order_id = 'x'",
                    "DELETE FROM stock",
                    "UPDATE reservation_line SET quantity = 1",
                ).forEach { sql ->
                    shouldThrow<SQLException> { connection.createStatement().use { it.execute(sql) } }.sqlState shouldBe "42501"
                }
            }
            // Debezium はこの DB に接続できる(Outbox の発行。マイグレーションが CONNECT を付ける)
            service.query(
                "SELECT has_database_privilege('${InventorySchema.CDC_ROLE}', current_database(), 'CONNECT')",
            ) shouldBe "t"
        }
    })

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
