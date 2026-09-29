package io.eia.platform.audit.canonical

import io.eia.platform.audit.AuditRecord
import io.eia.platform.audit.ChainHash
import io.eia.platform.audit.TestChains
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.time.Instant
import java.util.HexFormat

/**
 * 直列化の版 1 の仕様(ADR-0017 の表)を固定する。
 *
 * [GOLDEN_HEX] と [GOLDEN_SHA256] は、Kotlin の実装とは別に、仕様の表から Python で組み立てた値(2026-09-28)。
 * 実装を変えてこのテストが失敗したら、形式が変わっている。保存済みの記録を検証できなくなるため、版 1 は変えずに新しい版を足すこと。
 */
class CanonicalFormV1Spec :
    FunSpec({
        val fixed =
            AuditRecord(
                seq = 1,
                canonicalVersion = 1,
                occurredAt = Instant.parse("2026-09-28T01:02:03.456789Z"),
                recordedAt = Instant.parse("2026-09-28T01:02:04Z"),
                actorType = "service",
                actorId = "order-service",
                action = "order.create",
                targetType = "order",
                targetId = "ord-1",
                destination = null,
                outcome = "success",
                payloadSha256 = null,
                payloadRef = "",
                correlationId = "c-1",
                traceparent = null,
                // キーの順序は UTF-8 のバイト列の辞書順(a < b < ä)。a の値は NULL
                details = linkedMapOf("b" to "2", "a" to null, "ä" to "ü"),
                prevHash = ChainHash.GENESIS.hex,
                hash = "",
            )

        test("固定の入力に対するバイト列とハッシュが仕様どおり(形式の変更を検出する)") {
            HexFormat.of().formatHex(CanonicalFormV1.encode(fixed)) shouldBe GOLDEN_HEX
            CanonicalFormV1.hash(fixed).hex shouldBe GOLDEN_SHA256
        }

        test("hash 列の値は直列化の入力に含まれない") {
            CanonicalFormV1.hash(fixed.copy(hash = "f".repeat(64))) shouldBe CanonicalFormV1.hash(fixed)
        }

        test("NULL と空文字列を区別する") {
            val nullRef = fixed.copy(payloadRef = null)
            CanonicalFormV1.hash(nullRef) shouldNotBe CanonicalFormV1.hash(fixed)
            val emptyDetail = fixed.copy(details = fixed.details + ("a" to ""))
            CanonicalFormV1.hash(emptyDetail) shouldNotBe CanonicalFormV1.hash(fixed)
        }

        test("欄の境界をずらしても同じバイト列にならない(長さを前に付ける)") {
            val a = fixed.copy(actorId = "ab", action = "c")
            val b = fixed.copy(actorId = "a", action = "bc")
            CanonicalFormV1.hash(a) shouldNotBe CanonicalFormV1.hash(b)
        }

        test("details の格納順によらない") {
            val reversed =
                fixed.copy(
                    details =
                        LinkedHashMap(
                            fixed.details.entries
                                .reversed()
                                .associate { it.key to it.value },
                        ),
                )
            CanonicalFormV1.encode(reversed) shouldBe CanonicalFormV1.encode(fixed)
        }

        test("時刻はマイクロ秒で表し、マイクロ秒未満は負の無限大の向きに切り捨てる") {
            CanonicalFormV1.epochMicros(Instant.parse("2026-09-28T01:02:03.456789Z")) shouldBe 1_790_557_323_456_789L
            CanonicalFormV1.epochMicros(Instant.parse("2026-09-28T01:02:03.456789999Z")) shouldBe 1_790_557_323_456_789L
            CanonicalFormV1.epochMicros(Instant.ofEpochSecond(-1, 999_999_999)) shouldBe -1L
        }

        test("同じ入力からは常に同じバイト列になる(property)") {
            checkAll(
                Arb.string(0..64).orNull(),
                Arb.string(0..64).orNull(),
                Arb.string(0..16).map { it.ifEmpty { "k" } },
            ) { ref, value, key ->
                val record = TestChains.record(1).copy(payloadRef = ref, details = mapOf(key to value))
                CanonicalFormV1.encode(record) shouldBe CanonicalFormV1.encode(record.copy())
            }
        }

        test("版から直列化の方法を選ぶ。未知の版は null") {
            CanonicalForms.forVersion(1) shouldBe CanonicalFormV1
            CanonicalForms.forVersion(2).shouldBeNull()
            CanonicalForms.CURRENT.version shouldBe 1
        }
    }) {
    companion object {
        const val GOLDEN_HEX: String =
            "454941462d4155444954000101000000080000000000000001010000004030303030303030303030303030303030303030303030" +
                "303030303030303030303030303030303030303030303030303030303030303030303030303030303030010000000800065c809d" +
                "36f115010000000800065c809d3f3b00010000000773657276696365010000000d6f726465722d73657276696365010000000c6f" +
                "726465722e63726561746501000000056f7264657201000000056f72642d31000100000007737563636573730001000000000100" +
                "000003632d31000100000003010000000161000100000001620100000001320100000002c3a40100000002c3bc"
        const val GOLDEN_SHA256: String = "0099e7205a143a189453dde73b1d03f2ee9f9f0877ab4411923f66e228e4a756"
    }
}
