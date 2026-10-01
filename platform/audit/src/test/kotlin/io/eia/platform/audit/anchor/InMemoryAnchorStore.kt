package io.eia.platform.audit.anchor

import io.eia.platform.audit.AuditError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.time.Clock
import java.time.Instant

/** メモリ上のアンカーの保存先。ストレージの時刻は [clock]、保持モードは COMPLIANCE として記録する。 */
internal class InMemoryAnchorStore(
    private val clock: Clock,
) : AnchorStore {
    val versions = mutableListOf<AnchorVersion>()
    var failure: AuditError? = null
    var latestReads = 0
        private set

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

    override fun latest(prefix: String): Result<AnchorVersion?, AuditError> {
        failure?.let { return err(it) }
        latestReads++
        return ok(versions.filter { it.key.startsWith(prefix) && !it.isDeleteMarker }.lastOrNull())
    }
}
