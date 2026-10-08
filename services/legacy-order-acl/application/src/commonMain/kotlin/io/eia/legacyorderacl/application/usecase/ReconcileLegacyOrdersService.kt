package io.eia.legacyorderacl.application.usecase

import io.eia.legacyorderacl.application.port.inbound.Mismatch
import io.eia.legacyorderacl.application.port.inbound.ReconcileLegacyOrdersUseCase
import io.eia.legacyorderacl.application.port.inbound.ReconciliationReport
import io.eia.legacyorderacl.application.port.outbound.Fingerprints
import io.eia.legacyorderacl.application.port.outbound.LegacySource
import io.eia.legacyorderacl.application.port.outbound.Pause
import io.eia.legacyorderacl.application.port.outbound.PublishedLegacyOrders
import io.eia.legacyorderacl.application.port.outbound.ReconcileScope
import io.eia.legacyorderacl.application.port.outbound.SourceSnapshot
import io.eia.legacyorderacl.domain.LegacyOrder
import io.eia.legacyorderacl.domain.LegacyOrderFingerprint
import io.eia.legacyorderacl.domain.LegacyOrderTranslation
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import kotlin.time.Duration

/**
 * [ReconcileLegacyOrdersUseCase] の実装(ADR-0027)。
 *
 * 1 回の比較: レガシーを 1 つのスナップショットで読み(位置 X。読み終えたらトランザクションを閉じる)→ CDC が X より先まで取り込むのを待つ
 * → ACL が生の CDC の末尾まで処理するのを待って、出力の最新の状態を読む → キーごとに比べる。
 * レガシーの行は ACL と同じ変換の規則(domain)を通し、同じ形([LegacyOrderFingerprint])のハッシュで比べる。
 *
 * @param recheckAfter 食い違ったキーを比べ直すまでの待ち(X のころにコミットしたトランザクションなど、時点のわずかなずれを除く)
 */
public class ReconcileLegacyOrdersService(
    private val source: LegacySource,
    private val published: PublishedLegacyOrders,
    private val fingerprints: Fingerprints,
    private val pause: Pause,
    private val recheckAfter: Duration,
) : ReconcileLegacyOrdersUseCase {
    override suspend fun invoke(): Result<ReconciliationReport, DomainError> =
        compare(ReconcileScope.All).flatMap { first ->
            if (first.mismatches.isEmpty()) {
                ok(first.report(first.mismatches, fingerprints))
            } else {
                pause.pause(recheckAfter)
                compare(ReconcileScope.Keys(first.mismatches.keys)).map { second ->
                    // 比べ直しで一致した(または変換できなくなった)キーは、ずれから外す
                    val confirmed = second.mismatches.filterKeys { it in first.mismatches }
                    first
                        .copy(position = second.position, unconvertible = first.unconvertible + second.unconvertible)
                        .report(confirmed, fingerprints)
                }
            }
        }

    private data class Comparison(
        val position: String,
        val hashes: Map<String, String>,
        val mismatches: Map<String, Mismatch>,
        val unconvertible: Set<String>,
    ) {
        /** 全体のハッシュは、キーの順に「キー:ハッシュ」を改行でつないだ文字列の SHA-256。 */
        fun report(
            drift: Map<String, Mismatch>,
            fingerprints: Fingerprints,
        ): ReconciliationReport {
            val digest = fingerprints.sha256(hashes.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}:${it.value}" })
            return ReconciliationReport(
                position = position,
                compared = hashes.size,
                drift = drift.entries.sortedBy { it.key }.associate { it.key to it.value },
                unconvertible = unconvertible.sorted().toSet(),
                digest = digest,
            )
        }
    }

    private suspend fun compare(scope: ReconcileScope): Result<Comparison, DomainError> =
        source.read(scope).flatMap { snapshot ->
            source
                .awaitCaptured(snapshot.position)
                .flatMap { published.readCaughtUp(scope) }
                .map { outputs -> classify(snapshot, outputs) }
        }

    private fun classify(
        snapshot: SourceSnapshot,
        outputs: Map<String, LegacyOrder>,
    ): Comparison {
        val sources = mutableMapOf<String, LegacyOrder>()
        val unconvertible = mutableSetOf<String>()
        snapshot.rows.forEach { row ->
            when (val translated = LegacyOrderTranslation.translate(row)) {
                is Result.Ok -> sources[translated.value.orderNumber] = translated.value
                is Result.Err -> unconvertible += row.orderNumber.trimEnd(' ')
            }
        }
        val hashes = mutableMapOf<String, String>()
        val mismatches = mutableMapOf<String, Mismatch>()
        (sources.keys + outputs.keys + unconvertible).sorted().forEach { key ->
            val expected = sources[key]?.let { fingerprints.sha256(LegacyOrderFingerprint.canonical(it)) }
            val actual = outputs[key]?.let { fingerprints.sha256(LegacyOrderFingerprint.canonical(it)) }
            hashes[key] = expected ?: actual ?: UNCONVERTIBLE_MARK
            mismatchOf(expected, actual)?.takeIf { key !in unconvertible }?.let { mismatches[key] = it }
        }
        return Comparison(snapshot.position, hashes, mismatches, unconvertible)
    }

    private fun mismatchOf(
        expected: String?,
        actual: String?,
    ): Mismatch? =
        when {
            expected != null && actual == null -> Mismatch.MISSING
            expected == null && actual != null -> Mismatch.EXTRA
            expected != actual -> Mismatch.STALE
            else -> null
        }

    private companion object {
        /** 変換できない行のキーの、全体のハッシュの中の印(状態のハッシュを持たない)。 */
        const val UNCONVERTIBLE_MARK = "unconvertible"
    }
}
