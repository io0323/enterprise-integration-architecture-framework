package io.eia.platform.batch

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.mapError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * ステップの処理に渡す、実行の情報と Checkpoint の読み書き。
 *
 * ステップは再実行安全に作る(Framework 7.4: Upsert かパーティションの置換)。ステップの結果を確定した後、
 * 成功の記録の前にプロセスが落ちると、再実行で同じステップをもう一度実行するため(ADR-0030 §3)。
 *
 * @property attempt 今の試行の回数(1 始まり)
 * @property generation 実行の世代(やり直しのたびに増える)
 * @property trigger 実行を起動した情報(ファイルのキーなどは [RunTrigger.attributes])
 */
public class StepContext internal constructor(
    record: RunRecord,
    public val step: StepName,
    public val attempt: Int,
    private val store: BatchStore,
    private val clock: Clock,
) {
    public val run: RunKey = record.key
    public val generation: Int = record.generation
    public val trigger: RunTrigger = record.trigger

    /** このステップの、このパーティションでの途中の位置のキー。 */
    public val checkpointKey: CheckpointKey get() = CheckpointKey(run.dag, run.partition, step.value)

    /** DAG 全体の Watermark のキー(パーティションをまたいで残る)。 */
    public fun watermarkKey(name: String): CheckpointKey = CheckpointKey(run.dag, null, name)

    public suspend fun checkpoint(): Result<String?, DomainError> = read(checkpointKey)

    public suspend fun saveCheckpoint(value: String): Result<Unit, DomainError> = write(checkpointKey, value)

    public suspend fun watermark(name: String): Result<String?, DomainError> = read(watermarkKey(name))

    public suspend fun saveWatermark(
        name: String,
        value: String,
    ): Result<Unit, DomainError> = write(watermarkKey(name), value)

    // ステップの処理がそのまま返せるよう、失敗は DomainError にする
    private suspend fun read(key: CheckpointKey): Result<String?, DomainError> =
        withContext(Dispatchers.IO) { store.readCheckpoint(key) }.mapError { it.asDomainError() }

    private suspend fun write(
        key: CheckpointKey,
        value: String,
    ): Result<Unit, DomainError> =
        withContext(Dispatchers.IO) {
            store.writeCheckpoint(key, value, clock.now())
        }.mapError { it.asDomainError() }
}
