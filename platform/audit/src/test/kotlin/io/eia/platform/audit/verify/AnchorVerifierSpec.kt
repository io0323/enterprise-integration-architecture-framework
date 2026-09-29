package io.eia.platform.audit.verify

import io.eia.platform.audit.AuditRecord
import io.eia.platform.audit.TestChains
import io.eia.platform.audit.TestChains.rehash
import io.eia.platform.audit.TestChains.rows
import io.eia.platform.audit.anchor.Anchor
import io.eia.platform.audit.anchor.AnchorKeys
import io.eia.platform.audit.anchor.AnchorVersion
import io.eia.platform.audit.anchor.ServiceName
import io.eia.shared.kernel.getOrNull
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

private val SERVICE = ServiceName.parse("order").getOrNull()!!
private val MIN_RETENTION: Duration = Duration.ofDays(1)
private val CREATED: Instant = Instant.parse("2026-09-28T23:59:59.123456Z")
private val KEY = AnchorKeys.of(SERVICE, CREATED)

@Suppress("LongParameterList") // テストで変える項目(既定値つき。名前付き引数で指定する)
private fun anchorVersion(
    seq: Long,
    hash: String,
    versionId: String = "v1",
    mode: String? = "COMPLIANCE",
    retainUntil: Instant? = CREATED.plus(MIN_RETENTION),
    service: String = "order",
    createdAt: Instant = CREATED,
    key: String = KEY,
): AnchorVersion =
    AnchorVersion(
        key = key,
        versionId = versionId,
        lastModified = CREATED,
        isDeleteMarker = false,
        body = Anchor(service = service, seq = seq, hash = hash, canonicalVersion = 1, createdAt = createdAt.toString()).toJson(),
        retentionMode = mode,
        retainUntil = retainUntil,
    )

private val verifier = AnchorVerifier(SERVICE, MIN_RETENTION)

private fun check(
    versions: List<AnchorVersion>,
    records: List<AuditRecord>,
): List<Finding> {
    val chain = ChainVerifier(verifier.checkpoints(versions)).apply { records.rows().forEach(::accept) }.result()
    return chain.findings + verifier.verify(versions, chain)
}

class AnchorVerifierSpec :
    FunSpec({
        val chain = TestChains.chain(5)

        test("キーの日付は UTC の日付") {
            KEY shouldBe "anchors/order/2026-09-28.json"
            AnchorKeys.of(SERVICE, Instant.parse("2026-09-28T15:00:00Z")) shouldBe "anchors/order/2026-09-28.json"
            AnchorKeys.of(SERVICE, Instant.parse("2026-09-29T00:00:00Z")) shouldBe "anchors/order/2026-09-29.json"
        }

        test("アンカーと一致するチェーンでは何も検出しない") {
            check(listOf(anchorVersion(3, chain[2].hash), anchorVersion(5, chain[4].hash, versionId = "v2")), chain).shouldBeEmpty()
        }

        test("末尾の記録の書き換え(hash を計算し直す)を、アンカーとの不一致で検出する") {
            val tampered = chain.toMutableList().also { it[4] = it[4].copy(outcome = "failure").rehash() }
            check(listOf(anchorVersion(5, chain[4].hash)), tampered) shouldContainExactly listOf(Finding.AnchorHashMismatch(KEY, "v1", 5))
        }

        test("アンカーより後の記録を末尾から削除すると検出する") {
            check(listOf(anchorVersion(5, chain[4].hash)), chain.take(3)) shouldContainExactly
                listOf(Finding.AnchorRecordMissing(KEY, "v1", 5, 3))
        }

        test("全件を削除すると検出する") {
            check(listOf(anchorVersion(5, chain[4].hash)), emptyList()) shouldContainExactly
                listOf(Finding.AnchorRecordMissing(KEY, "v1", 5, null))
        }

        test("同じキーの全版を照合する(古い版の不一致も検出する)") {
            val tampered = chain.toMutableList().also { it[2] = it[2].copy(targetId = "x").rehash() }
            val findings = check(listOf(anchorVersion(3, chain[2].hash, "v1"), anchorVersion(5, chain[4].hash, "v2")), tampered)
            findings shouldContainExactly listOf(Finding.BrokenLink(4), Finding.AnchorHashMismatch(KEY, "v1", 3))
        }

        test("削除マーカーを検出する") {
            val marker = AnchorVersion(KEY, "m1", CREATED, true, null, null, null)
            check(listOf(anchorVersion(5, chain[4].hash), marker), chain) shouldContainExactly listOf(Finding.AnchorDeleteMarker(KEY, "m1"))
        }

        test("保持モードが COMPLIANCE でなければ検出する") {
            check(listOf(anchorVersion(5, chain[4].hash, mode = "GOVERNANCE")), chain) shouldContainExactly
                listOf(Finding.AnchorNotCompliance(KEY, "v1", "GOVERNANCE"))
            check(listOf(anchorVersion(5, chain[4].hash, mode = null, retainUntil = null)), chain) shouldContainExactly
                listOf(Finding.AnchorNotCompliance(KEY, "v1", null), Finding.AnchorRetentionTooShort(KEY, "v1"))
        }

        test("保持期限が最小値より短ければ検出する(時計のずれは許容する)") {
            val short = CREATED.plus(MIN_RETENTION).minus(Duration.ofHours(1))
            check(listOf(anchorVersion(5, chain[4].hash, retainUntil = short)), chain) shouldContainExactly
                listOf(Finding.AnchorRetentionTooShort(KEY, "v1"))
            val withinSkew = CREATED.plus(MIN_RETENTION).minus(Duration.ofMinutes(4))
            check(listOf(anchorVersion(5, chain[4].hash, retainUntil = withinSkew)), chain).shouldBeEmpty()
        }

        test("サービス名・キーの日付が一致しないアンカーを検出する") {
            check(listOf(anchorVersion(5, chain[4].hash, service = "payment")), chain).single().shouldBeInstanceOf<Finding.AnchorInvalid>()
            val otherDay = CREATED.minus(Duration.ofDays(1))
            check(listOf(anchorVersion(5, chain[4].hash, createdAt = otherDay)), chain).single().shouldBeInstanceOf<Finding.AnchorInvalid>()
        }

        test("読めない・形式が不正なアンカーを検出する") {
            val broken = anchorVersion(5, chain[4].hash).copy(body = "{".toByteArray())
            check(listOf(broken), chain).single().shouldBeInstanceOf<Finding.AnchorInvalid>()
            val unreadable = anchorVersion(5, chain[4].hash).copy(body = null, readError = "取得できません(AccessDenied)")
            check(listOf(unreadable), chain) shouldContainExactly listOf(Finding.AnchorInvalid(KEY, "v1", "取得できません(AccessDenied)"))
            val otherFormat =
                anchorVersion(
                    5,
                    chain[4].hash,
                ).copy(
                    body =
                        Anchor(
                            format = "x",
                            service = "order",
                            seq = 5,
                            hash = chain[4].hash,
                            canonicalVersion = 1,
                            createdAt = CREATED.toString(),
                        ).toJson(),
                )
            check(listOf(otherFormat), chain).single().shouldBeInstanceOf<Finding.AnchorInvalid>()
        }
    })
