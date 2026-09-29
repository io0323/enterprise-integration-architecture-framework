package io.eia.platform.audit.anchor

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.AuditStorageUnavailable
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.audit.jdbc.FakeAuditDb
import io.eia.platform.audit.jdbc.appendAll
import io.eia.platform.audit.verify.AuditVerification
import io.eia.platform.audit.verify.Finding
import io.eia.platform.audit.verify.VerificationReport
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.ok
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val SERVICE = ServiceName.parse("order").getOrNull()!!
private val RETENTION: Duration = Duration.ofDays(1)

/** メモリ上のアンカーの保存先。ストレージの時刻は [clock]、保持モードは COMPLIANCE として記録する。 */
private class InMemoryAnchorStore(
    private val clock: Clock,
) : AnchorStore {
    val versions = mutableListOf<AnchorVersion>()
    var failure: AuditError? = null

    override fun put(
        key: String,
        body: ByteArray,
        retainUntil: Instant,
    ): Result<String, AuditError> {
        failure?.let { return err(it) }
        val id = "v${versions.size + 1}"
        versions += AnchorVersion(key, id, clock.instant(), false, body, "COMPLIANCE", retainUntil)
        return ok(id)
    }

    override fun listVersions(prefix: String): Result<List<AnchorVersion>, AuditError> =
        failure?.let { err(it) } ?: ok(versions.filter { it.key.startsWith(prefix) })
}

class AnchorFlowSpec :
    FunSpec({
        // 23:59:59.9 UTC に保存したアンカーは、その日の日付のキーになる(日本時間では翌日)
        val clock = Clock.fixed(Instant.parse("2026-09-28T23:59:59.900Z"), ZoneOffset.UTC)
        val log = AuditLog(clock)

        fun verify(
            db: FakeAuditDb,
            store: AnchorStore,
        ): VerificationReport = AuditVerification(SERVICE, store, RETENTION).run(db.connection()).getOrNull().shouldNotBeNull()

        test("記録がなければアンカーを保存しない") {
            val store = InMemoryAnchorStore(clock)
            AnchorPublisher(SERVICE, store, RETENTION, clock).publish(FakeAuditDb().connection()).getOrNull().shouldBeNull()
            store.versions.shouldBeEmpty()
        }

        test("チェーンの末尾を anchors/{service}/{UTC の日付}.json に、保持期限つきで保存する") {
            val db = FakeAuditDb()
            val records = db.appendAll(log, 3)
            val store = InMemoryAnchorStore(clock)
            val published = AnchorPublisher(SERVICE, store, RETENTION, clock).publish(db.connection()).getOrNull().shouldNotBeNull()
            published.key shouldBe "anchors/order/2026-09-28.json"
            published.anchor.seq shouldBe 3
            published.anchor.hash shouldBe records.last().hash
            published.anchor.createdAt shouldBe "2026-09-28T23:59:59.900Z"
            published.retainUntil shouldBe clock.instant().plus(RETENTION)
            Anchor.parse(store.versions.single().body!!).getOrNull() shouldBe published.anchor

            val report = verify(db, store)
            report.findings.shouldBeEmpty()
            report.intact shouldBe true
            report.recordCount shouldBe 3
            report.headSeq shouldBe 3
            report.anchorVersionCount shouldBe 1
        }

        test("アンカーより後の記録の削除を検出する") {
            val db = FakeAuditDb()
            db.appendAll(log, 3)
            val store = InMemoryAnchorStore(clock)
            val published = AnchorPublisher(SERVICE, store, RETENTION, clock).publish(db.connection()).getOrNull()!!
            db.rows.removeAt(2)
            val report = verify(db, store)
            report.intact shouldBe false
            report.findings shouldContainExactly listOf(Finding.AnchorRecordMissing(published.key, published.versionId, 3, 2))
        }

        test("seq を NULL にした行(読めない行)があれば、表の件数との不一致で検出する") {
            val db = FakeAuditDb()
            db.appendAll(log, 3)
            db.rows += db.rows[2].toMutableMap().apply { this["seq"] = null }
            verify(db, InMemoryAnchorStore(clock)).findings shouldContainExactly listOf(Finding.RowCountMismatch(4, 3))
        }

        test("保存先・DB の失敗をそのまま返す") {
            val db = FakeAuditDb()
            db.appendAll(log, 1)
            val store = InMemoryAnchorStore(clock).apply { failure = AuditStorageUnavailable("S3", "HTTP 503") }
            AnchorPublisher(
                SERVICE,
                store,
                RETENTION,
                clock,
            ).publish(db.connection()).shouldBeInstanceOf<Result.Err<AuditStorageUnavailable>>()
            AuditVerification(SERVICE, store, RETENTION).run(db.connection()).shouldBeInstanceOf<Result.Err<AuditStorageUnavailable>>()
            store.failure = null
            db.failWith = "08006"
            AnchorPublisher(SERVICE, store, RETENTION, clock).publish(db.connection()).shouldBeInstanceOf<Result.Err<AuditError>>()
            db.failWith = "08006"
            AuditVerification(SERVICE, store, RETENTION).run(db.connection()).shouldBeInstanceOf<Result.Err<AuditError>>()
        }

        test("保持期間は正の期間だけを受け付ける") {
            shouldThrow<IllegalArgumentException> { AnchorPublisher(SERVICE, InMemoryAnchorStore(clock), Duration.ZERO, clock) }
        }

        test("サービス名の形式") {
            ServiceName.parse("Order").shouldBeInstanceOf<Result.Err<*>>()
            ServiceName.parse("b2b-gateway").getOrNull()?.value shouldBe "b2b-gateway"
        }
    })
