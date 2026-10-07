@file:Suppress("MagicNumber") // テストデータの値・乱数の長さ

package io.eia.platform.outbox

import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.Result
import io.eia.shared.resilience.trace.TraceParent
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun <E> Result<*, E>.err(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

private const val OWNER_ROLE = "svc_owner"
private const val APP_ROLE = "svc_app"
private const val CDC_ROLE = "debezium"
private val TOPIC = EventTopic.of("test.parcel.shipped.v1")

private fun record(
    id: Uuid,
    aggregateId: String = "p-1",
): OutboxRecord =
    OutboxRecord(
        TOPIC,
        "parcel",
        aggregateId,
        EventMetadata(
            id = id,
            source = "/test/parcel-service",
            type = TOPIC.ceType,
            time = Instant.parse("2026-10-07T01:02:03.456789Z"),
            traceParent = TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").let { (it as Result.Ok).value },
            correlationId = CorrelationId.parse("corr-1").let { (it as Result.Ok).value },
        ),
        byteArrayOf(0, 0, 0, 0, 7, 2, 65),
    )

/**
 * Outbox の既定の方式(INSERT の直後に DELETE)を、実際の PostgreSQL(images.env の版。`wal_level=logical`)で確かめる(ADR-0007)。
 *
 * ロールはローカル基盤と同じ構成にする: DB の所有者(マイグレーション)・アプリ用(INSERT / DELETE)・Debezium 用(REPLICATION と SELECT)。
 * WAL に INSERT が残ることは、`test_decoding` の論理レプリケーションのスロットで読んで確かめる(Debezium の pgoutput と同じく WAL を論理的に
 * 読む。発行そのものは P06 ③ の統合テストで確かめる)。
 */
class OutboxIT :
    FunSpec({
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical")
                .also { it.start() }
        afterSpec { postgres.stop() }

        val ownerPassword = randomHex()
        val appPassword = randomHex()
        val cdcPassword = randomHex()
        val databases = AtomicInteger()

        fun dataSource(
            database: String,
            user: String,
            password: String,
        ) = PGSimpleDataSource().apply {
            setURL("jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database")
            this.user = user
            this.password = password
        }

        fun superuser(database: String = postgres.databaseName): PGSimpleDataSource =
            dataSource(database, postgres.username, postgres.password)

        superuser().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $OWNER_ROLE LOGIN PASSWORD '$ownerPassword'")
                statement.execute("CREATE ROLE $APP_ROLE LOGIN PASSWORD '$appPassword'")
                // infra/local/postgres/init/20-debezium.sh と同じ: REPLICATION と、DB への CONNECT だけ
                statement.execute("CREATE ROLE $CDC_ROLE LOGIN REPLICATION PASSWORD '$cdcPassword'")
            }
        }

        /** サービスの DB を 1 つ作り、所有者でマイグレーションする。業務の表 `public.parcel` も作る(アプリに INSERT を付ける)。 */
        fun newDatabase(): String {
            val name = "outbox_it_${databases.incrementAndGet()}"
            superuser().connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE DATABASE $name OWNER $OWNER_ROLE")
                    statement.execute("REVOKE ALL ON DATABASE $name FROM PUBLIC")
                    statement.execute("GRANT CONNECT ON DATABASE $name TO $APP_ROLE, $CDC_ROLE")
                }
            }
            val owner = dataSource(name, OWNER_ROLE, ownerPassword)
            OutboxSchema.migrate(owner, APP_ROLE, CDC_ROLE).ok()
            owner.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE public.parcel (id text PRIMARY KEY)")
                    statement.execute("GRANT INSERT, SELECT ON public.parcel TO $APP_ROLE")
                }
            }
            return name
        }

        fun app(database: String) = dataSource(database, APP_ROLE, appPassword)

        fun cdc(database: String) = dataSource(database, CDC_ROLE, cdcPassword)

        /** WAL を論理的に読むスロット(test_decoding)を作る。作った時点より後の変更だけが読める。 */
        fun createSlot(database: String) {
            superuser(database).connection.use { connection ->
                connection.createStatement().use { it.execute("SELECT pg_create_logical_replication_slot('outbox_it', 'test_decoding')") }
            }
        }

        /** スロットの変更を読み進める(読んだ分は消費する)。各行は test_decoding の出力。 */
        fun changes(database: String): List<String> =
            superuser(database).connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT data FROM pg_logical_slot_get_changes('outbox_it', NULL, NULL)").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                }
            }

        fun dropSlot(database: String) {
            superuser(database).connection.use { connection ->
                connection.createStatement().use { it.execute("SELECT pg_drop_replication_slot('outbox_it')") }
            }
        }

        fun count(
            connection: Connection,
            sql: String,
        ): Long =
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }

        test("業務の更新と同じトランザクションで確定すると、表に行は残らず、WAL には業務の行と同じトランザクションの INSERT が残る") {
            val database = newDatabase()
            createSlot(database)
            try {
                val first = Uuid.parse("0199b6a0-0000-7000-8000-000000000001")
                val second = Uuid.parse("0199b6a0-0000-7000-8000-000000000002")
                app(database).connection.use { connection ->
                    connection.autoCommit = false
                    connection.createStatement().use { it.execute("INSERT INTO public.parcel VALUES ('p-1')") }
                    Outbox().append(connection, listOf(record(first), record(second, "p-2"))).ok()
                    connection.commit()
                }

                superuser(database).connection.use { count(it, "SELECT count(*) FROM outbox.outbox") } shouldBe 0
                val changes = changes(database)
                // 1 つのトランザクション: BEGIN → 業務の INSERT → Outbox の INSERT 2 件 → DELETE 2 件 → COMMIT
                changes.first() shouldContain "BEGIN"
                changes.last() shouldContain "COMMIT"
                changes.count { it.startsWith("BEGIN") } shouldBe 1
                changes.filter { it.startsWith("table public.parcel: INSERT") }.size shouldBe 1
                val inserts = changes.filter { it.startsWith("table outbox.outbox: INSERT") }
                inserts.size shouldBe 2
                inserts[0] shouldContain "id[uuid]:'$first'"
                inserts[0] shouldContain "topic[text]:'test.parcel.shipped.v1'"
                inserts[0] shouldContain "aggregate_id[text]:'p-1'"
                inserts[0] shouldContain "event_type[text]:'test.parcel.shipped'"
                inserts[0] shouldContain "payload[bytea]:'\\x00000000070241'"
                inserts[0] shouldContain "traceparent[text]:'00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'"
                inserts[0] shouldContain "ce_id[uuid]:'$first'"
                inserts[1] shouldContain "aggregate_id[text]:'p-2'"
                changes.filter { it.startsWith("table outbox.outbox: DELETE") }.size shouldBe 2
            } finally {
                dropSlot(database)
            }
        }

        test("業務がロールバックすれば、Outbox の INSERT も WAL に確定せず、発行されない") {
            val database = newDatabase()
            createSlot(database)
            try {
                app(database).connection.use { connection ->
                    connection.autoCommit = false
                    connection.createStatement().use { it.execute("INSERT INTO public.parcel VALUES ('p-9')") }
                    Outbox().append(connection, listOf(record(Uuid.parse("0199b6a0-0000-7000-8000-000000000009")))).ok()
                    connection.rollback()
                }

                changes(database).filter { it.startsWith("table ") }.shouldBeEmpty()
            } finally {
                dropSlot(database)
            }
        }

        test("Exposed のトランザクションからも書ける(appendOutbox)。自動コミットの接続は OutboxMisuse で、何も書かない") {
            val database = newDatabase()
            val exposed = Database.connect(app(database))
            transaction(exposed) {
                exec("INSERT INTO public.parcel VALUES ('p-3')")
                Outbox().appendOutbox(this, listOf(record(Uuid.parse("0199b6a0-0000-7000-8000-000000000003")))).ok()
            }
            superuser(database).connection.use { count(it, "SELECT count(*) FROM public.parcel") } shouldBe 1

            app(database).connection.use { connection ->
                connection.autoCommit = true
                Outbox()
                    .append(connection, listOf(record(Uuid.parse("0199b6a0-0000-7000-8000-000000000004"))))
                    .err()
                    .shouldBeInstanceOf<OutboxMisuse>()
            }
        }

        test("アプリのロールは INSERT と DELETE だけ: UPDATE・TRUNCATE・ペイロードの読み取りは拒否される") {
            val database = newDatabase()
            app(database).connection.use { connection ->
                listOf(
                    "UPDATE outbox.outbox SET topic = 'x.y.z.v1'",
                    "TRUNCATE outbox.outbox",
                    "SELECT payload FROM outbox.outbox",
                    "ALTER TABLE outbox.outbox ADD COLUMN extra text",
                ).forEach { sql ->
                    shouldThrow<SQLException> { connection.createStatement().use { it.execute(sql) } }.sqlState shouldBe "42501"
                }
            }
        }

        test("Debezium のロールは Outbox の表の SELECT だけ。publication は Outbox の表だけを含む") {
            val database = newDatabase()
            cdc(database).connection.use { connection ->
                count(connection, "SELECT count(*) FROM outbox.outbox") shouldBe 0
                shouldThrow<SQLException> {
                    connection.createStatement().use { it.execute("DELETE FROM outbox.outbox") }
                }.sqlState shouldBe "42501"
                shouldThrow<SQLException> {
                    connection.createStatement().use { it.execute("SELECT * FROM public.parcel") }
                }.sqlState shouldBe "42501"
            }
            superuser(database).connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT schemaname || '.' || tablename FROM pg_publication_tables WHERE pubname = 'eiaf_outbox'",
                        ).use { rows ->
                            buildList { while (rows.next()) add(rows.getString(1)) }
                        }
                } shouldContainExactly listOf("outbox.outbox")
            }
        }

        test("マイグレーションは何度実行しても同じ結果になる。ロールの名前の不正は拒否する") {
            val database = newDatabase()
            val owner = dataSource(database, OWNER_ROLE, ownerPassword)
            OutboxSchema.migrate(owner, APP_ROLE, CDC_ROLE).ok()
            OutboxSchema.migrate(owner, "app; DROP TABLE x", CDC_ROLE).err().shouldBeInstanceOf<OutboxMisuse>()
            OutboxSchema.migrate(owner, APP_ROLE, APP_ROLE).err().shouldBeInstanceOf<OutboxMisuse>()
        }
    })

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
