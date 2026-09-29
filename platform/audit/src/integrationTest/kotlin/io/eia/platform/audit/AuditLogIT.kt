@file:Suppress("MagicNumber", "ForEachOnRange") // テストデータの件数・seq・範囲

package io.eia.platform.audit

import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.audit.jdbc.AuditLogReader
import io.eia.platform.audit.jdbc.appendAudit
import io.eia.platform.audit.verify.ChainResult
import io.eia.platform.audit.verify.ChainVerifier
import io.eia.platform.audit.verify.Finding
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val INSUFFICIENT_PRIVILEGE = "42501"

internal fun sampleEvent(
    n: Int,
    details: Map<String, String> = mapOf("order.status" to "created"),
): AuditEvent =
    AuditEvent(
        occurredAt = Instant.now(),
        actor = Actor(ActorType.SERVICE, "order-service"),
        action = "order.create",
        target = AuditTarget("order", "ord-$n"),
        outcome = AuditOutcome.SUCCESS,
        destination = "kafka:sales.order.created.v1",
        payloadRef = "orders/ord-$n",
        details = details,
    )

internal fun AuditDatabase.append(
    log: AuditLog,
    n: Int,
): AuditRecord = appTransaction { log.append(it, sampleEvent(n)).getOrNull() ?: error("追記に失敗しました") }

internal fun AuditDatabase.verifyChain(): ChainResult =
    app.connection.use { connection ->
        val verifier = ChainVerifier()
        AuditLogReader.forEachRow(connection, pageSize = 7, consumer = verifier::accept).getOrNull() ?: error("読み込みに失敗しました")
        verifier.result()
    }

private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql) }

class AuditLogIT :
    FunSpec({
        val env = AuditEnvironment()
        beforeSpec { env.start() }
        afterSpec { env.close() }
        val log = AuditLog()

        test("アプリ用のロールで追記したチェーンが検証を通る(先頭の prev_hash は 64 個の 0)") {
            val db = env.newDatabase()
            val records = (1..20).map { db.append(log, it) }
            records.map { it.seq } shouldBe (1L..20L).toList()
            records.first().prevHash shouldBe ChainHash.GENESIS.hex
            val result = db.verifyChain()
            result.findings.shouldBeEmpty()
            result.count shouldBe 20
        }

        test("Exposed のトランザクションで追記できる。ロールバックすると記録は残らず、欠番もできない") {
            val db = env.newDatabase()
            val database = Database.connect(db.app)
            transaction(database) { log.appendAudit(this, sampleEvent(1)).shouldBeInstanceOf<Result.Ok<AuditRecord>>() }
            transaction(database) {
                log.appendAudit(this, sampleEvent(2)).shouldBeInstanceOf<Result.Ok<AuditRecord>>()
                rollback()
            }
            val third = transaction(database) { log.appendAudit(this, sampleEvent(3)).getOrNull()!! }
            third.seq shouldBe 2
            db.verifyChain().findings.shouldBeEmpty()
        }

        test("アプリ用のロールの UPDATE / DELETE / TRUNCATE は permission denied になる") {
            val db = env.newDatabase()
            db.append(log, 1)
            listOf(
                "UPDATE audit.audit_log SET actor_id = 'x'",
                "DELETE FROM audit.audit_log",
                "TRUNCATE audit.audit_log",
            ).forEach { sql ->
                val error = shouldThrow<SQLException> { db.app.connection.use { it.execute(sql) } }
                error.sqlState shouldBe INSUFFICIENT_PRIVILEGE
                error.message shouldContain "permission denied"
            }
        }

        test("所有者のロールの UPDATE / DELETE / TRUNCATE もトリガーで拒否される") {
            val db = env.newDatabase()
            db.append(log, 1)
            listOf(
                "UPDATE audit.audit_log SET actor_id = 'x'",
                "DELETE FROM audit.audit_log",
                "TRUNCATE audit.audit_log",
            ).forEach { sql ->
                val error = shouldThrow<SQLException> { db.owner.connection.use { it.execute(sql) } }
                error.sqlState shouldBe INSUFFICIENT_PRIVILEGE
                error.message shouldContain "追記専用"
            }
        }

        test("superuser がトリガーを外して UPDATE すると、検証で検出する") {
            val db = env.newDatabase()
            (1..5).forEach { db.append(log, it) }
            db.tamper("UPDATE audit.audit_log SET outcome = 'failure' WHERE seq = 3")
            db.verifyChain().findings shouldContainExactly listOf(Finding.HashMismatch(3))
        }

        test("superuser がトリガーを外して途中の記録を DELETE すると、検証で検出する") {
            val db = env.newDatabase()
            (1..5).forEach { db.append(log, it) }
            db.tamper("DELETE FROM audit.audit_log WHERE seq = 2")
            db.verifyChain().findings shouldContainExactly listOf(Finding.MissingSeq(2, 2), Finding.BrokenLink(3))
        }

        test("NULL を空文字列に書き換える改竄を検出する") {
            val db = env.newDatabase()
            (1..3).forEach { db.append(log, it) }
            db.tamper("UPDATE audit.audit_log SET traceparent = '' WHERE seq = 2")
            db.verifyChain().findings shouldContainExactly listOf(Finding.HashMismatch(2))
        }

        test("details を JSON として解釈できない値に書き換えると、解釈できない行として検出する") {
            val db = env.newDatabase()
            (1..3).forEach { db.append(log, it) }
            db.tamper("""UPDATE audit.audit_log SET details = '{"order.status": 1}' WHERE seq = 2""")
            db
                .verifyChain()
                .findings
                .single()
                .shouldBeInstanceOf<Finding.MalformedRecord>()
                .seq shouldBe 2
        }

        test("トランザクションの外(自動コミット)や REPEATABLE READ では追記しない") {
            val db = env.newDatabase()
            db.app.connection.use { connection ->
                connection.autoCommit = true
                (log.append(connection, sampleEvent(1)) as Result.Err).error.shouldBeInstanceOf<AuditMisuse>()
            }
            db.app.connection.use { connection ->
                connection.autoCommit = false
                connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                (log.append(connection, sampleEvent(1)) as Result.Err).error.shouldBeInstanceOf<AuditMisuse>()
                connection.rollback()
            }
        }

        test("並行して追記しても、欠番・重複・分岐のないチェーンになる") {
            val db = env.newDatabase()
            val threads = 8
            val perThread = 25
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val futures = (0 until threads).map { t -> pool.submit { repeat(perThread) { db.append(log, t * perThread + it) } } }
                futures.forEach { it.get(2, TimeUnit.MINUTES) }
            } finally {
                pool.shutdownNow()
            }
            val result = db.verifyChain()
            result.findings.shouldBeEmpty()
            result.count shouldBe threads * perThread.toLong()
            result.headSeq shouldBe threads * perThread.toLong()
        }

        test("details の値は伏せてから保存される") {
            val db = env.newDatabase()
            val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlLXNpZ25hdHVyZQ"
            db.appTransaction { log.append(it, sampleEvent(1, mapOf("auth" to "Bearer $jwt"))).getOrNull()!! }
            val stored =
                db.superuser { connection ->
                    connection.createStatement().use { s ->
                        s.executeQuery("SELECT details::text FROM audit.audit_log").use {
                            it.next()
                            it.getString(1)
                        }
                    }
                }
            stored shouldNotContain jwt
            db.verifyChain().findings.shouldBeEmpty()
        }
    })
