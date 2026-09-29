package io.eia.platform.audit.verify

import io.eia.platform.audit.AuditRecord
import io.eia.platform.audit.ChainHash
import io.eia.platform.audit.StoredRow
import io.eia.platform.audit.TestChains
import io.eia.platform.audit.TestChains.rehash
import io.eia.platform.audit.TestChains.rows
import io.eia.platform.audit.canonical.CanonicalForm
import io.eia.platform.audit.canonical.CanonicalForms
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private fun verify(
    rows: List<StoredRow>,
    checkpoints: Set<Long> = emptySet(),
): ChainResult = ChainVerifier(checkpoints).apply { rows.forEach(::accept) }.result()

class ChainVerifierSpec :
    FunSpec({
        test("正しいチェーンでは何も検出しない") {
            val result = verify(TestChains.chain(5).rows(), checkpoints = setOf(3))
            result.findings.shouldBeEmpty()
            result.count shouldBe 5
            result.headSeq shouldBe 5
            result.observedHashes.keys shouldContainExactly setOf(3L)
        }

        test("空のチェーンでは何も検出しない") {
            val result = verify(emptyList())
            result.findings.shouldBeEmpty()
            result.headSeq shouldBe null
        }

        test("列の値の書き換え(hash はそのまま)を検出する") {
            val chain = TestChains.chain(5).toMutableList()
            chain[2] = chain[2].copy(actorId = "attacker")
            verify(chain.rows()).findings shouldContainExactly listOf(Finding.HashMismatch(3))
        }

        test("書き換えて hash も計算し直すと、次の記録の prev_hash でつながらない") {
            val chain = TestChains.chain(5).toMutableList()
            chain[2] = chain[2].copy(outcome = "failure").rehash()
            verify(chain.rows()).findings shouldContainExactly listOf(Finding.BrokenLink(4))
        }

        test("末尾の記録の書き換えと hash の計算し直しはチェーンだけでは検出できない(アンカーで検出する)") {
            val chain = TestChains.chain(5).toMutableList()
            chain[4] = chain[4].copy(outcome = "failure").rehash()
            verify(chain.rows()).findings.shouldBeEmpty()
        }

        test("NULL を空文字列に書き換える改竄を検出する") {
            val chain = TestChains.chain(3).toMutableList()
            chain[1].traceparent shouldBe null
            chain[1] = chain[1].copy(traceparent = "")
            verify(chain.rows()).findings shouldContainExactly listOf(Finding.HashMismatch(2))
        }

        test("details の値の NULL と空文字列の書き換えを検出する") {
            val chain = TestChains.chain(3).map { it.copy(details = mapOf("note" to null)).rehash() }.relink()
            verify(chain.rows()).findings.shouldBeEmpty()
            val tampered = chain.toMutableList().also { it[0] = it[0].copy(details = mapOf("note" to "")) }
            verify(tampered.rows()).findings shouldContainExactly listOf(Finding.HashMismatch(1))
        }

        test("途中の記録の削除(欠番)を検出する") {
            val chain = TestChains.chain(5).filterNot { it.seq == 3L }
            verify(chain.rows()).findings shouldContainExactly listOf(Finding.MissingSeq(3, 3), Finding.BrokenLink(4))
        }

        test("先頭の記録の削除を検出する(prev_hash が 64 個の 0 でない)") {
            val chain = TestChains.chain(3).drop(1)
            verify(chain.rows()).findings shouldContainExactly listOf(Finding.MissingSeq(1, 1), Finding.BrokenLink(2))
        }

        test("順序の入れ替え(読み出しの順)を検出する") {
            val chain = TestChains.chain(4)
            val swapped = listOf(chain[0], chain[2], chain[1], chain[3])
            val findings = verify(swapped.rows()).findings
            findings shouldContain Finding.MissingSeq(2, 2)
            findings shouldContain Finding.OutOfOrder(2, 3)
        }

        test("2 件の seq を入れ替える改竄(内容はそのまま)を検出する") {
            val chain = TestChains.chain(4)
            val swapped = listOf(chain[0], chain[2].copy(seq = 2), chain[1].copy(seq = 3), chain[3])
            verify(swapped.rows()).findings shouldContainExactly
                listOf(
                    Finding.BrokenLink(2),
                    Finding.HashMismatch(2),
                    Finding.BrokenLink(3),
                    Finding.HashMismatch(3),
                    Finding.BrokenLink(4),
                )
        }

        test("重複した seq を検出する") {
            val chain = TestChains.chain(2)
            verify((chain + chain[1]).rows()).findings shouldContain Finding.OutOfOrder(2, 2)
        }

        test("未知の canonical_version を検出する") {
            val chain = TestChains.chain(2).toMutableList()
            chain[1] = chain[1].copy(canonicalVersion = 99)
            verify(chain.rows()).findings shouldContainExactly listOf(Finding.UnknownCanonicalVersion(2, 99))
        }

        test("canonical_version の書き換え(版 1 → 版 2)を、版ごとの直列化で検出する") {
            val chain = TestChains.chain(2)
            val v2 =
                object : CanonicalForm {
                    override val version = 2

                    override fun encode(record: AuditRecord) = byteArrayOf(2)
                }
            val forms = { v: Int -> if (v == 2) v2 else CanonicalForms.forVersion(v) }
            val tampered = listOf(chain[0], chain[1].copy(canonicalVersion = 2))
            val result = ChainVerifier(canonicalForms = forms).apply { tampered.rows().forEach(::accept) }.result()
            result.findings shouldContainExactly listOf(Finding.HashMismatch(2))
        }

        test("解釈できない行を報告し、以降の検証を続ける") {
            val chain = TestChains.chain(3)
            val rows =
                listOf(
                    StoredRow.Parsed(chain[0]),
                    StoredRow.Malformed(2, chain[1].hash, chain[1].prevHash, "details が不正"),
                    StoredRow.Parsed(chain[2]),
                )
            verify(rows).findings shouldContainExactly listOf(Finding.MalformedRecord(2, "details が不正"))
        }
    })

private fun List<AuditRecord>.relink(): List<AuditRecord> {
    var prev = ChainHash.GENESIS.hex
    return map { record ->
        record.copy(prevHash = prev).rehash().also { prev = it.hash }
    }
}
