package io.eia.legacyorderacl.application.usecase

import io.eia.legacyorderacl.application.port.inbound.Mismatch
import io.eia.legacyorderacl.application.port.inbound.ReconciliationReport
import io.eia.legacyorderacl.application.port.inbound.ResyncLegacyOrdersUseCase
import io.eia.legacyorderacl.application.port.inbound.ResyncResult
import io.eia.legacyorderacl.application.port.outbound.LegacySource
import io.eia.legacyorderacl.application.port.outbound.ReconcileScope
import io.eia.legacyorderacl.application.port.outbound.ReconcileTombstones
import io.eia.legacyorderacl.application.port.outbound.SnapshotRequests
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok

/**
 * [ResyncLegacyOrdersUseCase] の実装(ADR-0027 §6)。
 *
 * @param limit 1 回の照合で取り直すキーの上限(既定 100)。ずれのキーがこれを超えたら何もしない
 */
public class ResyncLegacyOrdersService(
    private val source: LegacySource,
    private val snapshots: SnapshotRequests,
    private val tombstones: ReconcileTombstones,
    private val limit: Int = DEFAULT_LIMIT,
) : ResyncLegacyOrdersUseCase {
    init {
        require(limit > 0) { "limit は 1 以上にしてください" }
    }

    override suspend fun invoke(report: ReconciliationReport): Result<ResyncResult, DomainError> {
        val drift = report.drift
        return when {
            drift.isEmpty() -> ok(ResyncResult.NothingToDo)
            drift.size > limit -> ok(ResyncResult.OverLimit(drift.size, limit))
            else -> resync(drift)
        }
    }

    private suspend fun resync(drift: Map<String, Mismatch>): Result<ResyncResult, DomainError> {
        val toSnapshot = drift.filterValues { it != Mismatch.EXTRA }.keys
        val extra = drift.filterValues { it == Mismatch.EXTRA }.keys
        val requested = if (toSnapshot.isEmpty()) ok(Unit) else snapshots.requestSnapshot(toSnapshot)
        return requested
            .flatMap { removeExtra(extra) }
            .map { (tombstoned, reappeared) -> ResyncResult.Requested(toSnapshot, tombstoned, reappeared) }
    }

    /** レガシーにないことを確かめ直してから、tombstone を書く。確かめ直したらあったキーは書かない。 */
    private suspend fun removeExtra(extra: Set<String>): Result<Pair<Set<String>, Set<String>>, DomainError> {
        if (extra.isEmpty()) return ok(emptySet<String>() to emptySet())
        return source.read(ReconcileScope.Keys(extra)).flatMap { snapshot ->
            val present = snapshot.rows.map { it.orderNumber.trimEnd(' ') }.toSet()
            val absent = extra - present
            var failure: DomainError? = null
            for (key in absent.sorted()) {
                (tombstones.delete(key) as? Result.Err)?.let { failure = it.error }
                if (failure != null) break
            }
            failure?.let { err(it) } ?: ok(absent to (extra intersect present))
        }
    }

    public companion object {
        public const val DEFAULT_LIMIT: Int = 100
    }
}
