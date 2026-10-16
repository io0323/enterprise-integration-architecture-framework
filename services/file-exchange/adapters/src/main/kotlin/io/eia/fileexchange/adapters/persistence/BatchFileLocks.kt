package io.eia.fileexchange.adapters.persistence

import io.eia.fileexchange.application.port.outbound.FileLocks
import io.eia.platform.batch.BatchStore
import io.eia.platform.batch.DagId
import io.eia.platform.batch.PartitionKey
import io.eia.platform.batch.RunKey
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ファイルの名前ごとのロック。ファイルの到着で動く受け取りの実行([dag] とファイルの名前のパーティション)と同じ advisory lock
 * (ADR-0030 §2)を使うので、見回りと受け取りの実行が同じファイルを同時に扱うことはない。
 */
public class BatchFileLocks(
    private val store: BatchStore,
    private val dag: DagId,
) : FileLocks {
    @Suppress("ReturnCount") // ロックの失敗・ほかが持っているときは、実行せずに返す
    override suspend fun <T> withLock(
        name: String,
        block: suspend () -> Result<T, DomainError>,
    ): Result<T?, DomainError> {
        val lease =
            when (val locked = withContext(Dispatchers.IO) { store.tryLock(RunKey(dag, PartitionKey(name))) }) {
                is Result.Err -> return err(locked.error.asDomainError())
                is Result.Ok -> locked.value ?: return ok(null)
            }
        return try {
            block()
        } finally {
            withContext(Dispatchers.IO) { lease.close() }
        }
    }
}
