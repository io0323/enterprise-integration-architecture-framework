@file:Suppress("MagicNumber") // テストデータの値・乱数の長さ

package io.eia.platform.inbox

import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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
private const val GROUP = "inventory.command"
private const val TOPIC = "inventory.stock.cmd-reserve.v1"

/**
 * 冪等消費の記録を、実際の PostgreSQL(images.env の版)で確かめる(ADR-0028 §3)。
 * ロールはサービスと同じ構成にする: DB の所有者(マイグレーション)・アプリ用(INSERT / DELETE と条件の列の SELECT)。
 */
class InboxIT :
    FunSpec({
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres")).also { it.start() }
        afterSpec { postgres.stop() }

        val ownerPassword = randomHex()
        val appPassword = randomHex()
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

        fun superuser(database: String = postgres.databaseName) = dataSource(database, postgres.username, postgres.password)

        superuser().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $OWNER_ROLE LOGIN PASSWORD '$ownerPassword'")
                statement.execute("CREATE ROLE $APP_ROLE LOGIN PASSWORD '$appPassword'")
            }
        }

        /** サービスの DB を 1 つ作り、所有者でマイグレーションする。業務の表 `public.reservation` も作る(アプリに INSERT を付ける)。 */
        fun newDatabase(): String {
            val name = "inbox_it_${databases.incrementAndGet()}"
            superuser().connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE DATABASE $name OWNER $OWNER_ROLE")
                    statement.execute("REVOKE ALL ON DATABASE $name FROM PUBLIC")
                    statement.execute("GRANT CONNECT ON DATABASE $name TO $APP_ROLE")
                }
            }
            val owner = dataSource(name, OWNER_ROLE, ownerPassword)
            InboxSchema.migrate(owner, APP_ROLE).ok()
            // 2 回目は何もしない(サービスの migrate は起動のたびに呼ばれうる)
            InboxSchema.migrate(owner, APP_ROLE).ok()
            owner.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE public.reservation (id text PRIMARY KEY)")
                    statement.execute("GRANT INSERT, SELECT ON public.reservation TO $APP_ROLE")
                }
            }
            return name
        }

        fun app(database: String) = dataSource(database, APP_ROLE, appPassword)

        fun count(
            database: String,
            sql: String,
        ): Long =
            superuser(database).connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
            }

        /** 業務の処理(予約)と同じトランザクションで記録し、重複なら業務の処理をしない。確定すれば true(重複なら false)。 */
        fun consume(
            connection: Connection,
            id: Uuid,
            reservation: String,
        ): Boolean {
            connection.autoCommit = false
            val receipt = Inbox().markProcessed(connection, GROUP, id, TOPIC).ok()
            if (receipt == Inbox.Receipt.FIRST) {
                connection.prepareStatement("INSERT INTO public.reservation VALUES (?)").use {
                    it.setString(1, reservation)
                    it.executeUpdate()
                }
            }
            connection.commit()
            return receipt == Inbox.Receipt.FIRST
        }

        test("同じメッセージの 2 回目は DUPLICATE で、業務の処理は 1 回だけ。別の Consumer Group では 1 回ずつ処理する") {
            val database = newDatabase()
            val id = Uuid.parse("0199b6a0-0000-7000-8000-000000000001")
            app(database).connection.use { connection ->
                consume(connection, id, "r-1") shouldBe true
                consume(connection, id, "r-1") shouldBe false

                connection.autoCommit = false
                Inbox().markProcessed(connection, "analytics.ingest", id, TOPIC).ok() shouldBe Inbox.Receipt.FIRST
                connection.commit()
            }
            count(database, "SELECT count(*) FROM public.reservation") shouldBe 1
            count(database, "SELECT count(*) FROM inbox.processed_message") shouldBe 2
        }

        test("業務がロールバックすれば記録も残らず、次の受信でやり直せる") {
            val database = newDatabase()
            val id = Uuid.parse("0199b6a0-0000-7000-8000-000000000002")
            app(database).connection.use { connection ->
                connection.autoCommit = false
                Inbox().markProcessed(connection, GROUP, id, TOPIC).ok() shouldBe Inbox.Receipt.FIRST
                connection.rollback()

                consume(connection, id, "r-2") shouldBe true
            }
            count(database, "SELECT count(*) FROM public.reservation") shouldBe 1
        }

        test("同じメッセージを並行に受け取っても、業務の処理は 1 回だけ(後の側は先の確定を待って DUPLICATE になる)") {
            val database = newDatabase()
            val id = Uuid.parse("0199b6a0-0000-7000-8000-000000000003")
            val parallel = 8
            val barrier = CyclicBarrier(parallel)
            val executor = Executors.newFixedThreadPool(parallel)
            try {
                val results =
                    (1..parallel)
                        .map { n ->
                            executor.submit(
                                Callable {
                                    app(database).connection.use { connection ->
                                        barrier.await(10, TimeUnit.SECONDS)
                                        consume(connection, id, "r-3-$n")
                                    }
                                },
                            )
                        }.map { it.get(30, TimeUnit.SECONDS) }
                results.count { it } shouldBe 1
            } finally {
                executor.shutdownNow()
            }
            count(database, "SELECT count(*) FROM public.reservation") shouldBe 1
        }

        test("Exposed のトランザクションからも記録できる(markProcessedIn)。自動コミットの接続は InboxMisuse で、何も書かない") {
            val database = newDatabase()
            val exposed = Database.connect(app(database))
            val id = Uuid.parse("0199b6a0-0000-7000-8000-000000000004")
            transaction(exposed) { Inbox().markProcessedIn(this, GROUP, id, TOPIC).ok() } shouldBe Inbox.Receipt.FIRST
            transaction(exposed) { Inbox().markProcessedIn(this, GROUP, id, TOPIC).ok() } shouldBe Inbox.Receipt.DUPLICATE

            app(database).connection.use { connection ->
                connection.autoCommit = true
                Inbox()
                    .markProcessed(connection, GROUP, Uuid.parse("0199b6a0-0000-7000-8000-000000000005"), TOPIC)
                    .err()
                    .shouldBeInstanceOf<InboxMisuse>()
            }
            count(database, "SELECT count(*) FROM inbox.processed_message") shouldBe 1
        }

        test("purgeExpired は DB の時計で保持期間を過ぎた行だけを、上限の件数ずつ古い順に消す") {
            val database = newDatabase()
            app(database).connection.use { connection ->
                for (n in 1..5) consume(connection, Uuid.parse("0199b6a0-0000-7000-8000-00000000010$n"), "r-$n")
            }
            // 3 件を 15 日前・14 日前 - 1 時間・13 日前に記録したことにする(所有者で書き換える)
            superuser(database).connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        UPDATE inbox.processed_message SET processed_at = clock_timestamp() - CASE message_id
                            WHEN '0199b6a0-0000-7000-8000-000000000101' THEN interval '15 days'
                            WHEN '0199b6a0-0000-7000-8000-000000000102' THEN interval '14 days 1 hour'
                            WHEN '0199b6a0-0000-7000-8000-000000000103' THEN interval '13 days'
                            ELSE interval '0' END
                        """.trimIndent(),
                    )
                }
            }
            app(database).connection.use { connection ->
                // 自動コミットのままでよい
                Inbox().purgeExpired(connection, 14.days, batchSize = 1).ok() shouldBe 1
                Inbox().purgeExpired(connection, 14.days, batchSize = 1).ok() shouldBe 1
                Inbox().purgeExpired(connection, 14.days, batchSize = 1).ok() shouldBe 0
                Inbox().purgeExpired(connection, 1.hours).ok() shouldBe 1
            }
            count(database, "SELECT count(*) FROM inbox.processed_message") shouldBe 2
            count(
                database,
                "SELECT count(*) FROM inbox.processed_message WHERE message_id IN " +
                    "('0199b6a0-0000-7000-8000-000000000104', '0199b6a0-0000-7000-8000-000000000105')",
            ) shouldBe 2
        }

        test("アプリのロールは INSERT と DELETE だけ: UPDATE・TRUNCATE・調査用の列の読み取り・DDL は拒否される") {
            val database = newDatabase()
            app(database).connection.use { connection ->
                listOf(
                    "UPDATE inbox.processed_message SET processed_at = now()",
                    "TRUNCATE inbox.processed_message",
                    "SELECT topic FROM inbox.processed_message",
                    "ALTER TABLE inbox.processed_message ADD COLUMN extra text",
                ).forEach { sql ->
                    shouldThrow<SQLException> { connection.createStatement().use { it.execute(sql) } }.sqlState shouldBe "42501"
                }
            }
        }

        test("Consumer Group の形式を DB の CHECK でも守る(所有者が直接書いても入らない)") {
            val database = newDatabase()
            superuser(database).connection.use { connection ->
                shouldThrow<SQLException> {
                    connection.createStatement().use {
                        it.execute(
                            "INSERT INTO inbox.processed_message (consumer_group, message_id, topic) VALUES ('x', gen_random_uuid(), 't')",
                        )
                    }
                }.sqlState shouldBe "23514"
            }
        }
    })

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
