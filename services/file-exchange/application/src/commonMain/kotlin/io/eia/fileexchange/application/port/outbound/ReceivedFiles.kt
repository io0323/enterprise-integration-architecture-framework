package io.eia.fileexchange.application.port.outbound

import io.eia.fileexchange.domain.ExtraArrival
import io.eia.fileexchange.domain.ExtraArrivalReason
import io.eia.fileexchange.domain.ReceivedFile
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** 受け取りの記録(file-exchange の DB。ADR-0032 §5)。 */
public interface ReceivedFiles {
    public suspend fun find(name: String): Result<ReceivedFile?, DomainError>

    /**
     * 移動の決定([file]。状態は MOVING)を記録する。同じ名前の記録がすでにあれば、書かずにそれを返す(DB の一意の制約。
     * 先に記録した方の決定に従う)。記録された行を返す。
     */
    public suspend fun begin(file: ReceivedFile): Result<ReceivedFile, DomainError>

    /** 移し終えた(MOVING → STORED)。 */
    public suspend fun stored(name: String): Result<Unit, DomainError>

    /**
     * 受け取り済みの名前で届いたファイルの処理を始める。同じ名前の処理中のものがあれば、書かずにそれを返す
     * (処理中は名前ごとに 1 つだけ。DB の一意の制約)。
     */
    public suspend fun beginExtra(
        name: String,
        sha256: String?,
        reason: ExtraArrivalReason,
    ): Result<ExtraArrival, DomainError>

    /** 処理中のまま残っている、受け取り済みの名前で届いたファイル(落ちた後の再開)。 */
    public suspend fun movingExtras(): Result<List<ExtraArrival>, DomainError>

    public suspend fun finishExtra(id: Long): Result<Unit, DomainError>
}

/** ファイルの名前ごとのロック。ファイルの到着で動く受け取りの実行と同じロック(同じファイルを同時に扱わない)。 */
public interface FileLocks {
    /** ロックを取れたら [block] を実行して結果を返す。ほかが持っていれば、実行せずに null。 */
    public suspend fun <T> withLock(
        name: String,
        block: suspend () -> Result<T, DomainError>,
    ): Result<T?, DomainError>
}
