package io.eia.platform.audit.verify

import io.eia.platform.audit.ChainHash
import io.eia.platform.audit.StoredRow
import io.eia.platform.audit.canonical.CanonicalForm
import io.eia.platform.audit.canonical.CanonicalForms
import io.eia.platform.audit.jdbc.AuditLogReader

/**
 * ハッシュチェーンを先頭から順に検証する。行は `seq` の昇順で 1 件ずつ [accept] に渡す(全件をメモリに載せない)。
 *
 * 検出するもの: 記録の書き換え([Finding.HashMismatch])、削除・差し込み([Finding.BrokenLink] / [Finding.MissingSeq])、
 * 順序の入れ替え([Finding.OutOfOrder])、未知の直列化の版、解釈できない行。
 *
 * [checkpoints] に指定した `seq` のハッシュ(保存された `hash` 列の値)を覚えておき、アンカーとの照合([AnchorVerifier])に使う。
 *
 * [start] を指定すると、その位置(前回のアンカーの `seq` と `hash`)の直後から検証する(差分の検証。AnchorCycle)。
 * 最初の行の `seq` は `start.seq + 1`、`prev_hash` は `start.hash` であることを確かめる。[start] より前の記録は検証しない
 * (`make audit-verify` の全体の検証で扱う。ADR-0017 §6)。
 */
public class ChainVerifier(
    private val checkpoints: Set<Long> = emptySet(),
    private val canonicalForms: (Int) -> CanonicalForm? = CanonicalForms::forVersion,
    start: AuditLogReader.Position? = null,
) {
    private val findings = mutableListOf<Finding>()
    private val observed = mutableMapOf<Long, String>()
    private var previousSeq: Long? = start?.seq
    private var previousHash: String = start?.hash ?: ChainHash.GENESIS.hex
    private var lastHash: String? = null
    private var count = 0L

    public fun accept(row: StoredRow) {
        checkOrder(row.seq)
        if (row.prevHash != previousHash) findings += Finding.BrokenLink(row.seq)
        when (row) {
            is StoredRow.Malformed -> {
                findings += Finding.MalformedRecord(row.seq, row.reason)
            }

            is StoredRow.Parsed -> {
                val form = canonicalForms(row.record.canonicalVersion)
                when {
                    form == null -> findings += Finding.UnknownCanonicalVersion(row.seq, row.record.canonicalVersion)
                    else -> checkHash(form, row)
                }
            }
        }
        if (row.seq in checkpoints) observed[row.seq] = row.hash
        // 以降は保存された hash でつなぐ(1 件の改竄で後続の全件を不一致にしない。改竄された位置を特定できるように)
        previousHash = row.hash
        previousSeq = maxOf(previousSeq ?: row.seq, row.seq)
        lastHash = row.hash
        count++
    }

    /** 直列化できない値(範囲外の時刻など)は、検証を止めずに解釈できない行として報告する。 */
    private fun checkHash(
        form: CanonicalForm,
        row: StoredRow.Parsed,
    ) {
        val hash =
            try {
                form.hash(row.record).hex
            } catch (e: ArithmeticException) {
                findings += Finding.MalformedRecord(row.seq, "直列化できない値があります(${e::class.simpleName})")
                return
            }
        if (hash != row.hash) findings += Finding.HashMismatch(row.seq)
    }

    private fun checkOrder(seq: Long) {
        val previous = previousSeq
        val expected = (previous ?: 0L) + 1
        when {
            previous != null && seq <= previous -> findings += Finding.OutOfOrder(seq, previous)
            seq > expected -> findings += Finding.MissingSeq(expected, seq - 1)
        }
    }

    public fun result(): ChainResult =
        ChainResult(
            count = count,
            headSeq = previousSeq.takeIf { count > 0 },
            observedHashes = observed.toMap(),
            findings = findings.toList(),
            lastHash = lastHash,
        )
}

/**
 * @property headSeq 読んだ中で最大の `seq`。記録がなければ null。
 * @property observedHashes チェックポイント(アンカーの `seq`)の、保存された `hash`。記録がなかった `seq` は含まない。
 * @property lastHash 最後に読んだ行の、保存された `hash`。記録がなければ null。
 */
public data class ChainResult(
    val count: Long,
    val headSeq: Long?,
    val observedHashes: Map<Long, String>,
    val findings: List<Finding>,
    val lastHash: String? = null,
)
