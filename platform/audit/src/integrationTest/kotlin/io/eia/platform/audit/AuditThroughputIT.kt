@file:Suppress("MagicNumber") // 計測の件数・同時実行数・百分率

package io.eia.platform.audit

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.platform.audit.jdbc.AuditLog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

private const val WARMUP = 200
private const val MEASURED = 2_000
private const val ROUNDS = 3
private val CONCURRENCY = listOf(1, 2, 4, 8, 16)

/** 1 回の計測の結果。[lockWaiters] は advisory lock を待っていた接続の数の平均(pg_stat_activity を数ミリ秒ごとに見る)。 */
private data class Round(
    val perSecond: Double,
    val lockWaiters: Double,
)

/**
 * 追記の性能の目安(ADR-0017・docs/reports/p04a-audit-throughput.md)。ローカルでの目安であり、本番の性能値ではない。
 *
 * - chained: [AuditLog.append](advisory lock で直列にし、末尾を読んでハッシュを計算して INSERT)を 1 トランザクション 1 件で実行する。
 * - plain: 同じ列の別のテーブルに、ロックもハッシュもなしで INSERT する(比較の基準)。
 * - 同時実行数ごとに [ROUNDS] 回計測し、中央値と最小・最大を出す。1 件あたりの平均の所要時間(同時実行数 ÷ 件数/秒)と、
 *   ロックを待っていた接続の数の平均も出す(直列にしたことによる待ちの量を見るため)。
 *
 * 結果は build/reports/audit/throughput.md に書く。値は実行環境で大きく変わるため、ここでは検証(欠番・分岐がないこと)だけを確かめる。
 */
class AuditThroughputIT :
    FunSpec({
        val env = AuditEnvironment()
        beforeSpec { env.start() }
        afterSpec { env.close() }

        test("同時実行数ごとの 1 秒あたりの追記件数を計測する") {
            val rows = mutableListOf<String>()
            CONCURRENCY.forEach { threads ->
                val db = env.newDatabase()
                db.owner.connection.use { it.createPlainTable() }
                pool(db, threads).use { pool ->
                    val log = AuditLog()
                    run(pool, threads, WARMUP) { connection, n -> log.append(connection, sampleEvent(n)) }
                    run(pool, threads, WARMUP) { connection, n -> connection.plainInsert(n) }
                    val chained = (1..ROUNDS).map { measure(db, pool, threads) { connection, n -> log.append(connection, sampleEvent(n)) } }
                    val plain = (1..ROUNDS).map { measure(db, pool, threads) { connection, n -> connection.plainInsert(n) } }
                    val chainedMedian = chained.map { it.perSecond }.median()
                    val plainMedian = plain.map { it.perSecond }.median()
                    rows +=
                        "| $threads | ${chained.map { it.perSecond }.summary()} | ${"%.2f".format(threads * 1000 / chainedMedian)} | " +
                        "${"%.1f".format(chained.map { it.lockWaiters }.median())} | ${plain.map { it.perSecond }.summary()} | " +
                        "${"%.0f".format(100 * chainedMedian / plainMedian)}% |"
                }
                val result = db.verifyChain()
                result.findings.shouldBeEmpty()
                result.count shouldBe (WARMUP + ROUNDS * MEASURED).toLong()
            }
            val report =
                buildString {
                    appendLine(
                        "| 同時実行数 | chained 件/秒 中央値(最小〜最大) | chained 1 件の平均時間(ms) | ロック待ちの接続数の平均 | " +
                            "plain 件/秒 中央値(最小〜最大) | chained / plain |",
                    )
                    appendLine("|---:|---:|---:|---:|---:|---:|")
                    rows.forEach(::appendLine)
                }
            println(report)
            val output = Path.of("build/reports/audit/throughput.md")
            Files.createDirectories(output.parent)
            Files.writeString(output, report)
        }
    })

private fun List<Double>.median(): Double = sorted()[size / 2]

private fun List<Double>.summary(): String = "${"%,.0f".format(median())}(${"%,.0f".format(min())}〜${"%,.0f".format(max())})"

private fun pool(
    db: AuditDatabase,
    threads: Int,
): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            dataSource = db.app
            maximumPoolSize = threads
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        },
    )

/** [MEASURED] 件を [threads] 並列で実行し、1 秒あたりの件数と、その間のロック待ちの接続数の平均を返す。1 件 = 1 トランザクション。 */
private fun measure(
    db: AuditDatabase,
    dataSource: DataSource,
    threads: Int,
    action: (Connection, Int) -> Unit,
): Round {
    val sampler = LockWaitSampler(db)
    val started = System.nanoTime()
    run(dataSource, threads, MEASURED, action)
    val seconds = (System.nanoTime() - started) / 1e9
    return Round(MEASURED / seconds, sampler.stop())
}

/** 別の接続(superuser)から pg_stat_activity を見て、advisory lock を待っている接続の数を数える。 */
private class LockWaitSampler(
    db: AuditDatabase,
) {
    @Volatile private var running = true
    private val samples = mutableListOf<Int>()
    private val thread =
        Thread {
            db.superuser { connection ->
                connection
                    .prepareStatement(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                            "AND wait_event_type = 'Lock' AND wait_event = 'advisory'",
                    ).use { statement ->
                        while (running) {
                            statement.executeQuery().use { rows ->
                                rows.next()
                                synchronized(samples) { samples += rows.getInt(1) }
                            }
                            Thread.sleep(2)
                        }
                    }
            }
        }.apply { start() }

    fun stop(): Double {
        running = false
        thread.join()
        return synchronized(samples) { if (samples.isEmpty()) 0.0 else samples.average() }
    }
}

private fun run(
    dataSource: DataSource,
    threads: Int,
    total: Int,
    action: (Connection, Int) -> Unit,
) {
    val next = AtomicInteger()
    val executor = Executors.newFixedThreadPool(threads)
    try {
        val futures =
            (0 until threads).map {
                executor.submit {
                    while (true) {
                        val n = next.getAndIncrement()
                        if (n >= total) break
                        dataSource.connection.use { connection ->
                            action(connection, n)
                            connection.commit()
                        }
                    }
                }
            }
        futures.forEach { it.get(5, TimeUnit.MINUTES) }
    } finally {
        executor.shutdownNow()
    }
}

private fun Connection.createPlainTable() {
    createStatement().use {
        it.execute("CREATE TABLE audit.plain_log (LIKE audit.audit_log INCLUDING DEFAULTS)")
        it.execute("ALTER TABLE audit.plain_log ADD PRIMARY KEY (seq)")
        it.execute("CREATE SEQUENCE audit.plain_seq")
        it.execute("GRANT INSERT, SELECT ON audit.plain_log TO ${AuditEnvironment.APP_ROLE}")
        it.execute("GRANT USAGE ON SEQUENCE audit.plain_seq TO ${AuditEnvironment.APP_ROLE}")
    }
}

private fun Connection.plainInsert(n: Int) {
    prepareStatement(
        """
        INSERT INTO audit.plain_log (seq, canonical_version, occurred_at, recorded_at, actor_type, actor_id, action, target_type, target_id,
            destination, outcome, payload_ref, details, prev_hash, hash)
        VALUES (nextval('audit.plain_seq'), 1, now(), now(), 'service', 'order-service', 'order.create', 'order', ?,
            'kafka:sales.order.created.v1', 'success', ?, '{"order.status":"created"}'::jsonb, repeat('0', 64), repeat('0', 64))
        """.trimIndent(),
    ).use {
        it.setString(1, "ord-$n")
        it.setString(2, "orders/ord-$n")
        it.executeUpdate()
    }
}
