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
private val CONCURRENCY = listOf(1, 4, 16)

/**
 * 追記の性能の目安(ADR-0017・docs/reports/p04a-audit-throughput.md)。
 *
 * - chained: [AuditLog.append](advisory lock で直列にし、末尾を読んでハッシュを計算して INSERT)を 1 トランザクション 1 件で実行する。
 * - plain: 同じ列の別のテーブルに、ロックもハッシュもなしで INSERT する(比較の基準)。
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
                    val chained = measure(pool, threads) { connection, n -> log.append(connection, sampleEvent(n)) }
                    val plain = measure(pool, threads) { connection, n -> connection.plainInsert(n) }
                    rows +=
                        "| $threads | ${"%,.0f".format(chained)} | ${"%,.0f".format(plain)} | ${"%.0f".format(100 * chained / plain)}% |"
                }
                val result = db.verifyChain()
                result.findings.shouldBeEmpty()
                result.count shouldBe (WARMUP + MEASURED).toLong()
            }
            val report =
                buildString {
                    appendLine("| 同時実行数 | chained(件/秒) | plain(件/秒) | chained / plain |")
                    appendLine("|---:|---:|---:|---:|")
                    rows.forEach(::appendLine)
                }
            println(report)
            val output = Path.of("build/reports/audit/throughput.md")
            Files.createDirectories(output.parent)
            Files.writeString(output, report)
        }
    })

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

/** [WARMUP] 件を捨ててから [MEASURED] 件を [threads] 並列で実行し、1 秒あたりの件数を返す。1 件 = 1 トランザクション。 */
private fun measure(
    dataSource: DataSource,
    threads: Int,
    action: (Connection, Int) -> Unit,
): Double {
    run(dataSource, threads, WARMUP, action)
    val started = System.nanoTime()
    run(dataSource, threads, MEASURED, action)
    val seconds = (System.nanoTime() - started) / 1e9
    return MEASURED / seconds
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
