package io.eia.platform.audit.jdbc

import io.eia.platform.audit.Actor
import io.eia.platform.audit.ActorType
import io.eia.platform.audit.AuditError
import io.eia.platform.audit.AuditEvent
import io.eia.platform.audit.AuditMisuse
import io.eia.platform.audit.AuditOutcome
import io.eia.platform.audit.AuditRecord
import io.eia.platform.audit.AuditStorageRejected
import io.eia.platform.audit.AuditStorageUnavailable
import io.eia.platform.audit.AuditTarget
import io.eia.platform.audit.ChainHash
import io.eia.platform.audit.InvalidAuditEvent
import io.eia.platform.audit.StoredRow
import io.eia.platform.audit.verify.ChainVerifier
import io.eia.platform.audit.verify.Finding
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.eia.shared.resilience.trace.TraceParent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00.123456789Z"), ZoneOffset.UTC)

internal fun testEvent(n: Int): AuditEvent =
    AuditEvent(
        occurredAt = Instant.parse("2026-09-28T09:00:00Z").plusSeconds(n.toLong()),
        actor = Actor(ActorType.USER, "user-$n"),
        action = "order.create",
        target = AuditTarget("order", "ord-$n"),
        outcome = AuditOutcome.SUCCESS,
        destination = "kafka:sales.order.created.v1",
        payloadSha256 = ChainHash.sha256("payload-$n".toByteArray()),
        correlationId = CorrelationId.parse("corr-$n").getOrNull(),
        traceparent = TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").getOrNull(),
        details = mapOf("order.status" to "created"),
    )

internal fun FakeAuditDb.appendAll(
    log: AuditLog,
    count: Int,
): List<AuditRecord> = (1..count).map { log.append(connection(), testEvent(it)).getOrNull()!! }

internal fun FakeAuditDb.verify(pageSize: Int = AuditLogReader.DEFAULT_PAGE_SIZE): List<Finding> {
    val verifier = ChainVerifier()
    AuditLogReader.forEachRow(connection(), pageSize, verifier::accept).getOrNull()!!
    return verifier.result().findings
}

private fun Result<AuditRecord, AuditError>.error(): AuditError = (this as Result.Err).error

class AuditLogSpec :
    FunSpec({
        val log = AuditLog(CLOCK)

        test("追記ごとにロックを取り、seq を 1 から振り、前の記録のハッシュでつなぐ") {
            val db = FakeAuditDb()
            val records = db.appendAll(log, 3)
            records.map { it.seq } shouldBe listOf(1L, 2L, 3L)
            records[0].prevHash shouldBe ChainHash.GENESIS.hex
            records[1].prevHash shouldBe records[0].hash
            records[2].prevHash shouldBe records[1].hash
            db.lockCount shouldBe 3
            records[0].recordedAt shouldBe Instant.parse("2026-09-28T10:00:00.123456Z")
        }

        test("保存した行を読み直して検証すると、改竄の疑いはない(ページの境界をまたいでも)") {
            val db = FakeAuditDb()
            db.appendAll(log, 5)
            db.verify(pageSize = 2).shouldBeEmpty()
        }

        test("保存した行の書き換えを検出する") {
            val db = FakeAuditDb()
            db.appendAll(log, 3)
            db.rows[1]["actor_id"] = "attacker"
            db.verify() shouldContainExactly listOf(Finding.HashMismatch(2))
        }

        test("details が JSON のオブジェクトでない行・prev_hash が NULL の行は解釈できない行になる。hash が NULL の行はページングで読まれない") {
            val db = FakeAuditDb()
            db.appendAll(log, 3)
            db.rows[0]["details"] = "[]"
            db.rows[1]["prev_hash"] = null
            db.rows[2]["hash"] = null
            val rows = mutableListOf<StoredRow>()
            // hash が NULL の行は (seq, hash) の比較に一致しない。表の件数との照合(countRows)で検出する(AnchorFlowSpec)
            AuditLogReader.forEachRow(db.connection(), consumer = rows::add).getOrNull() shouldBe 2L
            AuditLogReader.countRows(db.connection()).getOrNull() shouldBe 3L
            rows.forEach { it.shouldBeInstanceOf<StoredRow.Malformed>() }
        }

        test("記録できる範囲の外の時刻('infinity' など)の行は、検証を止めずに解釈できない行として報告する") {
            val db = FakeAuditDb()
            db.appendAll(log, 3)
            db.rows[1]["occurred_at"] = OffsetDateTime.MAX
            db.verify().map { it::class } shouldBe listOf(Finding.MalformedRecord::class)
        }

        test("主キーを外して、ページの境界の seq を重複させた行も検証する") {
            val db = FakeAuditDb()
            db.appendAll(log, 4)
            // pageSize = 2 の境界(seq = 2)に、内容の違う行を差し込む
            db.rows +=
                db.rows[1].toMutableMap().apply {
                    this["actor_id"] = "forged"
                    this["hash"] = "f".repeat(64)
                }
            db.verify(pageSize = 2) shouldContainExactly
                listOf(Finding.OutOfOrder(2, 2), Finding.BrokenLink(2), Finding.HashMismatch(2), Finding.BrokenLink(3))
        }

        test("記録がなければ末尾は null") {
            AuditLogReader.readHead(FakeAuditDb().connection()).getOrNull().shouldBeNull()
        }

        test("自動コミットが有効・READ COMMITTED でないときは追記しない") {
            val db = FakeAuditDb()
            log.append(db.connection(autoCommit = true), testEvent(1)).error().shouldBeInstanceOf<AuditMisuse>()
            db.isolation = "repeatable read"
            log.append(db.connection(), testEvent(1)).error().shouldBeInstanceOf<AuditMisuse>()
            db.rows.shouldBeEmpty()
            db.lockCount shouldBe 0
        }

        test("不正なイベントは DB に触れずに拒否する") {
            val db = FakeAuditDb()
            val event = testEvent(1).let { AuditEvent(it.occurredAt, it.actor, "Bad Action", it.target, it.outcome) }
            log.append(db.connection(), event).error().shouldBeInstanceOf<InvalidAuditEvent>()
            db.lockCount shouldBe 0
        }

        test("SQL の失敗は SQLSTATE で分類し、理由には SQLSTATE だけを入れる") {
            val db = FakeAuditDb()
            db.failWith = "08006"
            log
                .append(db.connection(), testEvent(1))
                .error()
                .shouldBeInstanceOf<AuditStorageUnavailable>()
                .reason shouldBe "SQLSTATE 08006"
            db.failWith = "42501"
            log
                .append(db.connection(), testEvent(1))
                .error()
                .shouldBeInstanceOf<AuditStorageRejected>()
                .reason shouldBe "SQLSTATE 42501"
            db.failWith = "40001"
            AuditLogReader.readHead(db.connection()).shouldBeInstanceOf<Result.Err<AuditStorageUnavailable>>()
        }

        test("アプリ用のロール名は識別子の形だけを受け付ける") {
            AuditSchema.migrate(dataSource = FakeDataSource, appRole = "app; DROP TABLE x").shouldBeInstanceOf<Result.Err<AuditMisuse>>()
        }
    })

/** [AuditSchema.migrate] のロール名の検査用(接続する前に拒否されることを確かめる)。 */
private object FakeDataSource : javax.sql.DataSource by java.lang.reflect.Proxy.newProxyInstance(
    javax.sql.DataSource::class.java.classLoader,
    arrayOf(javax.sql.DataSource::class.java),
    { _, method, _ -> throw UnsupportedOperationException(method.name) },
) as javax.sql.DataSource
