@file:Suppress("MagicNumber") // テストデータの件数・時刻・分

package io.eia.platform.batch

import io.eia.platform.batch.jdbc.BatchSchema
import io.eia.platform.batch.jdbc.JdbcBatchStore
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [JdbcBatchStore] と [BatchRunner] を実際の PostgreSQL で確かめる(ADR-0030 §2・§3)。
 * ロールはサービスの DB と同じ構成(所有者がマイグレーションし、アプリのロールは読み書きだけ)。
 */
class JdbcBatchStoreIT :
    FunSpec({
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        lateinit var owner: PGSimpleDataSource
        lateinit var app: PGSimpleDataSource
        lateinit var store: JdbcBatchStore
        val runtime =
            Observability.init(
                ObservabilityConfig.of("it-batch").getOrNull()!!,
                TelemetrySinks(),
                installLogAppender = false,
            )
        val t0 = Instant.parse("2026-10-13T16:00:00Z")
        val dagId = DagId("it.store")
        val counter = AtomicInteger()

        fun dataSource(
            user: String,
            password: String,
        ) = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            this.user = user
            this.password = password
        }

        fun superuser(sql: String) {
            dataSource(postgres.username, postgres.password).connection.use { it.createStatement().use { s -> s.execute(sql) } }
        }

        fun key(): RunKey = RunKey(dagId, PartitionKey("p-${counter.incrementAndGet()}"))

        beforeSpec {
            postgres.start()
            superuser("CREATE ROLE batch_owner LOGIN PASSWORD 'owner'")
            superuser("CREATE ROLE batch_app LOGIN PASSWORD 'app'")
            superuser("GRANT CREATE ON DATABASE ${postgres.databaseName} TO batch_owner")
            owner = dataSource("batch_owner", "owner")
            app = dataSource("batch_app", "app")
            BatchSchema.migrate(owner, "batch_app").getOrNull().shouldNotBeNull()
            // 2 回目は何もしない
            BatchSchema.migrate(owner, "batch_app").getOrNull().shouldNotBeNull()
            store = JdbcBatchStore(app)
            // 業務の結果の表(テスト用)。アプリのロールで書く
            superuser(
                "CREATE TABLE public.it_target (partition_key text NOT NULL, row_no int NOT NULL, PRIMARY KEY (partition_key, row_no))",
            )
            superuser("GRANT SELECT, INSERT, DELETE ON public.it_target TO batch_app")
        }

        afterSpec { postgres.stop() }

        test("マイグレーション: アプリのロールは読み書きだけ(表を消せない)。appRole の形式の誤りは Err") {
            val dropped =
                runCatchingSql { app.connection.use { it.createStatement().use { s -> s.execute("DROP TABLE batch.batch_run") } } }
            dropped shouldBe "42501"
            BatchSchema.migrate(owner, "Bad Role").shouldBeInstanceOf<Result.Err<BatchMisuse>>()
        }

        test("ロック: 同じ実行は 1 つだけ。外せば取り直せる。持っている接続が切れれば(プロセスが落ちれば)外れる") {
            val run = key()
            val lease = store.tryLock(run).getOrNull().shouldNotBeNull()
            store.tryLock(run).getOrNull().shouldBeNull()
            store
                .tryLock(key())
                .getOrNull()
                .shouldNotBeNull()
                .close()
            lease.close()

            store.tryLock(run).getOrNull().shouldNotBeNull()
            // kill: ロックを持つセッションを DB 側で切る
            superuser("SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype = 'advisory' AND pid <> pg_backend_pid()")
            store
                .tryLock(run)
                .getOrNull()
                .shouldNotBeNull()
                .close()
        }

        test("開始・再開・やり直し: 再開は起動の情報とステップを残し、やり直しは世代を増やしてステップとパーティションの Checkpoint を消す") {
            val run = key()
            val first = RunTrigger(TriggerKind.FILE, "00-${"a".repeat(32)}-${"b".repeat(16)}-01", "corr-1", mapOf("file.key" to "in/x.csv"))
            store.find(run).getOrNull().shouldBeNull()

            val begun = store.begin(run, first, "00-${"c".repeat(32)}-${"d".repeat(16)}-01", false, t0).getOrNull()!!
            begun shouldBe RunRecord(run, RunStatus.RUNNING, 1, first, "00-${"c".repeat(32)}-${"d".repeat(16)}-01", t0, null)
            store.stepStarted(run, StepName("load"), 1, t0).getOrNull().shouldNotBeNull()
            store.stepStarted(run, StepName("load"), 2, t0 + 1.minutes).getOrNull().shouldNotBeNull()
            store.stepFinished(run, StepName("load"), StepStatus.SUCCEEDED, t0 + 3.minutes).getOrNull().shouldNotBeNull()
            store.writeCheckpoint(CheckpointKey(dagId, run.partition, "load"), "row-3", t0).getOrNull().shouldNotBeNull()
            store.writeCheckpoint(CheckpointKey(dagId, null, "offset"), "7", t0).getOrNull().shouldNotBeNull()
            store.finish(run, RunStatus.FAILED, t0 + 4.minutes).getOrNull().shouldNotBeNull()

            val resumed = store.begin(run, RunTrigger.MANUAL, null, false, t0 + 10.minutes).getOrNull()!!
            resumed.status shouldBe RunStatus.RUNNING
            resumed.trigger shouldBe first
            resumed.startedAt shouldBe t0
            resumed.finishedAt.shouldBeNull()
            store.steps(run).getOrNull()!!.getValue(StepName("load")) shouldBe
                StepRecord(StepName("load"), StepStatus.SUCCEEDED, 2, t0, t0 + 3.minutes)

            val restarted = store.begin(run, RunTrigger.MANUAL, null, true, t0 + 20.minutes).getOrNull()!!
            restarted.generation shouldBe 2
            restarted.trigger shouldBe RunTrigger.MANUAL
            store.steps(run).getOrNull()!! shouldBe emptyMap()
            store.readCheckpoint(CheckpointKey(dagId, run.partition, "load")).getOrNull().shouldBeNull()
            store.readCheckpoint(CheckpointKey(dagId, null, "offset")).getOrNull() shouldBe "7"
        }

        test("実績と検索: 成功したステップの所要時間を新しい順に返し、状態と時刻で実行を探す") {
            val dag = DagId("it.history")
            listOf(3, 5, 4).forEachIndexed { index, minutes ->
                val run = RunKey(dag, PartitionKey("2026-10-0${index + 1}"))
                val start = t0 + (index * 60).minutes
                store.begin(run, RunTrigger.MANUAL, null, false, start).getOrNull().shouldNotBeNull()
                store.stepStarted(run, StepName("load"), 1, start).getOrNull().shouldNotBeNull()
                store.stepFinished(run, StepName("load"), StepStatus.SUCCEEDED, start + minutes.minutes).getOrNull().shouldNotBeNull()
                store.finish(run, RunStatus.SUCCEEDED, start + minutes.minutes).getOrNull().shouldNotBeNull()
            }

            store.recentDurations(dag, StepName("load"), 2).getOrNull() shouldContainExactly listOf(4.minutes, 5.minutes)
            store.runs(dag, RunStatus.SUCCEEDED, t0 + 60.minutes).getOrNull()!!.map { it.key.partition.value } shouldContainExactly
                listOf("2026-10-02", "2026-10-03")
            store.runs(dag, RunStatus.RUNNING, t0 - 1.days).getOrNull()!! shouldBe emptyList()
            store.recentDurations(dag, StepName("load"), 0).shouldBeInstanceOf<Result.Err<BatchMisuse>>()
            store
                .stepStarted(
                    RunKey(dag, PartitionKey("2026-10-01")),
                    StepName("load"),
                    0,
                    t0,
                ).shouldBeInstanceOf<Result.Err<BatchMisuse>>()
        }

        test("Checkpoint をステップの結果と同じトランザクションで書く: 取り消せば両方なく、確定すれば両方ある。自動コミットでは Err") {
            val checkpoint = CheckpointKey(dagId, PartitionKey("tx"), "load")
            app.connection.use { connection ->
                connection.autoCommit = false
                insertRows(connection, "tx", 2)
                store.writeCheckpoint(connection, checkpoint, "row-2", t0).getOrNull().shouldNotBeNull()
                connection.rollback()
            }
            store.readCheckpoint(checkpoint).getOrNull().shouldBeNull()

            app.connection.use { connection ->
                connection.autoCommit = false
                insertRows(connection, "tx", 2)
                store.writeCheckpoint(connection, checkpoint, "row-2", t0).getOrNull().shouldNotBeNull()
                connection.commit()
            }
            store.readCheckpoint(checkpoint).getOrNull() shouldBe "row-2"
            countRows(app, "tx") shouldBe 2

            app.connection.use { connection ->
                store.writeCheckpoint(connection, checkpoint, "x", t0).shouldBeInstanceOf<Result.Err<BatchMisuse>>()
            }
        }

        test("DB に接続できなければ、リトライできる失敗") {
            val broken = JdbcBatchStore(dataSource("batch_app", "wrong-password"))
            broken
                .find(key())
                .shouldBeInstanceOf<Result.Err<BatchError>>()
                .error.code shouldBe "batch_storage_rejected"
            val down = JdbcBatchStore(PGSimpleDataSource().apply { setUrl("jdbc:postgresql://127.0.0.1:1/none") })
            down.tryLock(key()).shouldBeInstanceOf<Result.Err<BatchError>>().error shouldBe BatchStorageUnavailable("SQLSTATE 08001")
        }

        test("DoD: 実行を途中で kill して再実行しても、結果の行は重複も欠損もない(成功したステップは再実行しない)") {
            val partition = PartitionKey("2026-10-13")
            val loads = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            var hang = true
            val dag =
                dag("it.kill") {
                    val load =
                        step("load") {
                            action { ctx ->
                                loads.incrementAndGet()
                                // パーティションの置換と Checkpoint を 1 つのトランザクションで確定する
                                withContext(Dispatchers.IO) {
                                    app.connection.use { connection ->
                                        connection.autoCommit = false
                                        connection.prepareStatement("DELETE FROM public.it_target WHERE partition_key = ?").use {
                                            it.setString(1, partition.value)
                                            it.executeUpdate()
                                        }
                                        insertRows(connection, partition.value, 5)
                                        store.writeCheckpoint(connection, ctx.checkpointKey, "rows=5", t0).getOrNull().shouldNotBeNull()
                                        connection.commit()
                                    }
                                }
                                ok(Unit)
                            }
                        }
                    step("verify", load) {
                        action {
                            if (hang) {
                                entered.complete(Unit)
                                awaitCancellation()
                            }
                            if (countRows(app, partition.value) == 5L) ok(Unit) else err(ConflictError("count"))
                        }
                    }
                }
            val runner = BatchRunner(JdbcBatchStore(app), runtime)

            coroutineScope {
                val killed = launch(Dispatchers.Default) { runner.run(dag, partition) }
                withTimeout(30.seconds) { entered.await() }
                killed.cancelAndJoin()
            }
            store.find(RunKey(dag.id, partition)).getOrNull()!!.status shouldBe RunStatus.RUNNING

            hang = false
            val outcome = runner.run(dag, partition).getOrNull().shouldBeInstanceOf<RunOutcome.Succeeded>()

            outcome.skipped shouldContainExactly listOf(StepName("load"))
            loads.get() shouldBe 1
            countRows(app, partition.value) shouldBe 5
            store.readCheckpoint(CheckpointKey(dag.id, partition, "load")).getOrNull() shouldBe "rows=5"

            // やり直しても、置換なので行は 5 のまま
            runner.run(dag, partition, mode = RunMode.RESTART).getOrNull().shouldBeInstanceOf<RunOutcome.Succeeded>()
            loads.get() shouldBe 2
            countRows(app, partition.value) shouldBe 5
        }
    })

private fun insertRows(
    connection: Connection,
    partition: String,
    rows: Int,
) {
    connection.prepareStatement("INSERT INTO public.it_target (partition_key, row_no) VALUES (?, ?)").use { statement ->
        for (row in 1..rows) {
            statement.setString(1, partition)
            statement.setInt(2, row)
            statement.addBatch()
        }
        statement.executeBatch()
    }
}

private fun countRows(
    dataSource: PGSimpleDataSource,
    partition: String,
): Long =
    dataSource.connection.use { connection ->
        val statement = connection.prepareStatement("SELECT count(*) FROM public.it_target WHERE partition_key = ?")
        statement.setString(1, partition)
        statement.executeQuery().use { rs -> rs.next().let { rs.getLong(1) } }
    }

/** SQL を実行し、失敗した SQLSTATE を返す(成功なら null)。 */
private fun runCatchingSql(block: () -> Unit): String? =
    try {
        block()
        null
    } catch (e: java.sql.SQLException) {
        e.sqlState
    }
