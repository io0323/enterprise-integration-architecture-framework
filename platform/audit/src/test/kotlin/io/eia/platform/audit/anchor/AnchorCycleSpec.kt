package io.eia.platform.audit.anchor

import io.eia.platform.audit.AuditMisuse
import io.eia.platform.audit.AuditStorageUnavailable
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.audit.jdbc.FakeAuditDb
import io.eia.platform.audit.jdbc.appendAll
import io.eia.platform.audit.jdbc.testEvent
import io.eia.platform.audit.verify.AuditVerification
import io.eia.platform.audit.verify.Finding
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private val SERVICE = ServiceName.parse("order").getOrNull()!!
private val RETENTION: Duration = Duration.ofDays(1)

/** 進められる時計(間隔ごとの検査を、待たずに再現する)。 */
internal class MutableClock(
    var now: Instant,
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

private class Fixture {
    val clock = MutableClock(Instant.parse("2026-10-02T00:00:00Z"))
    val db = FakeAuditDb()
    val log = AuditLog(clock)
    val store = InMemoryAnchorStore(clock)

    fun cycle(listener: AnchorCycleListener = AnchorCycleListener.NONE): AnchorCycle =
        AnchorCycle(SERVICE, store, AnchorPublisher(SERVICE, store, RETENTION, clock), listener, clock)

    fun append(
        from: Int,
        count: Int,
    ) {
        (from until from + count).forEach { log.append(db.connection(), testEvent(it)).getOrNull().shouldNotBeNull() }
    }

    fun AnchorCycle.check(): AnchorOutcome = run(db.connection(autoCommit = true)).getOrNull().shouldNotBeNull()

    /** 記録の列を書き換える(ハッシュは計算し直さない)。 */
    fun tamper(seq: Long) {
        db.rows.single { it["seq"] == seq }["actor_id"] = "tampered"
    }
}

class AnchorCycleSpec :
    FunSpec({
        test("記録がなければ empty で、保存しない") {
            with(Fixture()) {
                cycle().check() shouldBe AnchorOutcome.Empty
                store.versions.size shouldBe 0
            }
        }

        test("最初の回はチェーンの先頭から検証して末尾を保存し、記録が増えなければ unchanged で保存しない") {
            with(Fixture()) {
                db.appendAll(log, 3)
                val cycle = cycle()
                val published = cycle.check().shouldBeInstanceOf<AnchorOutcome.Published>()
                published.verifiedRecords shouldBe 3
                published.anchor.anchor.seq shouldBe 3
                repeat(3) { cycle.check() shouldBe AnchorOutcome.Unchanged(3) }
                store.versions shouldHaveSize 1
            }
        }

        test("2 回目からは、前回のアンカーの直後からの差分だけを検証して保存する") {
            with(Fixture()) {
                db.appendAll(log, 3)
                val cycle = cycle()
                cycle.check()
                append(from = 4, count = 2)
                val published = cycle.check().shouldBeInstanceOf<AnchorOutcome.Published>()
                published.verifiedRecords shouldBe 2
                published.anchor.anchor.seq shouldBe 5
                store.versions shouldHaveSize 2
                // 保存したアンカーは、全体の検証でも整合する
                AuditVerification(SERVICE, store, RETENTION).run(db.connection(autoCommit = true)).getOrNull()?.intact shouldBe true
            }
        }

        test("差分に改竄があれば保存せず、次の回も同じ起点から検証して拒否し続ける") {
            with(Fixture()) {
                db.appendAll(log, 3)
                val cycle = cycle()
                cycle.check()
                append(from = 4, count = 2)
                tamper(4)
                repeat(2) {
                    cycle.check() shouldBe AnchorOutcome.Rejected(listOf(Finding.HashMismatch(4)))
                }
                store.versions shouldHaveSize 1
            }
        }

        test("差分の先頭の prev_hash が前回のアンカーとつながらなければ拒否する(アンカーの直後の記録の差し替え)") {
            with(Fixture()) {
                db.appendAll(log, 3)
                val cycle = cycle()
                cycle.check()
                append(from = 4, count = 1)
                db.rows.single { it["seq"] == 4L }["prev_hash"] = "0".repeat(64)
                val rejected = cycle.check().shouldBeInstanceOf<AnchorOutcome.Rejected>()
                rejected.findings.map { it.code } shouldContainExactly listOf("broken_link", "hash_mismatch")
            }
        }

        test("前回のアンカーより前の改竄は、差分の検証では見ない(make audit-verify の全体の検証で見つける。ADR-0017 §6)") {
            with(Fixture()) {
                db.appendAll(log, 3)
                val cycle = cycle()
                cycle.check()
                tamper(2)
                append(from = 4, count = 1)
                cycle.check().shouldBeInstanceOf<AnchorOutcome.Published>()
                val report =
                    AuditVerification(
                        SERVICE,
                        store,
                        RETENTION,
                    ).run(db.connection(autoCommit = true)).getOrNull().shouldNotBeNull()
                report.findings shouldContainExactly listOf(Finding.HashMismatch(2))
            }
        }

        test("末尾からの削除と、前回のアンカーの記録の書き換えを拒否する") {
            with(Fixture()) {
                db.appendAll(log, 3)
                val cycle = cycle()
                val anchor = cycle.check().shouldBeInstanceOf<AnchorOutcome.Published>().anchor
                val removed = db.rows.removeAt(2)
                cycle.check() shouldBe AnchorOutcome.Rejected(listOf(Finding.AnchorRecordMissing(anchor.key, anchor.versionId, 3, 2)))
                db.rows += removed.toMutableMap().apply { this["hash"] = "f".repeat(64) }
                cycle.check() shouldBe AnchorOutcome.Rejected(listOf(Finding.AnchorHashMismatch(anchor.key, anchor.versionId, 3)))
                db.rows.clear()
                cycle.check() shouldBe AnchorOutcome.Rejected(listOf(Finding.AnchorRecordMissing(anchor.key, anchor.versionId, 3, null)))
                store.versions shouldHaveSize 1
            }
        }

        test("再起動の後は、ストレージで最後に保存された版を起点にする(版の内容は最初の回に 1 回だけ読む)") {
            with(Fixture()) {
                db.appendAll(log, 3)
                cycle().check()
                val restarted = cycle()
                restarted.check() shouldBe AnchorOutcome.Unchanged(3)
                append(from = 4, count = 1)
                restarted.check().shouldBeInstanceOf<AnchorOutcome.Published>().verifiedRecords shouldBe 1
                store.latestReads shouldBe 2
            }
        }

        test("最後に保存された版が解釈できなければ、起点にせず保存もしない") {
            with(Fixture()) {
                db.appendAll(log, 3)
                store.put("anchors/order/2026-10-02.json", "{}".toByteArray(), clock.instant().plus(RETENTION))
                val rejected = cycle().check().shouldBeInstanceOf<AnchorOutcome.Rejected>()
                rejected.findings.single().shouldBeInstanceOf<Finding.AnchorInvalid>()
                store.versions shouldHaveSize 1
            }
        }

        test("ストレージ・DB に届かなければ Err を返し、次の回でやり直せる") {
            with(Fixture()) {
                db.appendAll(log, 3)
                val cycle = cycle()
                store.failure = AuditStorageUnavailable("S3", "HTTP 503")
                cycle.run(db.connection(autoCommit = true)).shouldBeInstanceOf<Result.Err<AuditStorageUnavailable>>()
                store.failure = null
                db.failWith = "08006"
                cycle.run(db.connection(autoCommit = true)).shouldBeInstanceOf<Result.Err<*>>()
                cycle.check().shouldBeInstanceOf<AnchorOutcome.Published>()
            }
        }

        test("検査は自動コミットが有効な専用の接続でだけ行う") {
            with(Fixture()) {
                db.appendAll(log, 1)
                cycle().run(db.connection(autoCommit = false)).shouldBeInstanceOf<Result.Err<AuditMisuse>>()
            }
        }
    })
