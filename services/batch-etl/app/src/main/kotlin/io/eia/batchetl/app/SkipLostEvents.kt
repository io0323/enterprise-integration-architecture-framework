package io.eia.batchetl.app

import io.eia.batchetl.adapters.kafka.KafkaIngestPositions
import io.eia.batchetl.adapters.persistence.JdbcRawOrderStore
import io.eia.batchetl.application.port.outbound.SourcePosition
import io.eia.platform.batch.jdbc.JdbcBatchStore
import io.eia.shared.kernel.Result
import kotlinx.coroutines.runBlocking
import org.apache.kafka.common.TopicPartition
import org.koin.core.Koin
import org.slf4j.LoggerFactory
import kotlin.time.Clock

/**
 * `skip-lost-events`: 保持期間を過ぎて消えたイベントを飛ばし、取り込みを再開できるようにする
 * (docs/runbooks/batch-rerun.md の「保持期間を超えた回復」。ADR-0031 §7)。
 * 既定は表示だけ。[run] の `confirm` で取り込みの位置を書き換える。飛ばしたイベントの日は、週次の照合で DRIFT として見つかる。
 */
internal object SkipLostEvents {
    private val logger = LoggerFactory.getLogger(SkipLostEvents::class.java)

    fun run(
        koin: Koin,
        confirm: Boolean,
    ): Int {
        val store = koin.get<JdbcBatchStore>()
        return when (val found = find(koin, store)) {
            is Result.Err -> BatchEtlCommands.FAILED.also { logger.error("{}", found.error) }
            is Result.Ok -> apply(store, found.value.first, found.value.second, confirm)
        }
    }

    /** 消えた範囲を飛ばした位置と、消えた件数。 */
    private fun find(
        koin: Koin,
        store: JdbcBatchStore,
    ): Result<Pair<SourcePosition, Map<TopicPartition, Long>>, String> {
        val stored =
            when (val read = store.readCheckpoint(JdbcRawOrderStore.WATERMARK)) {
                is Result.Err -> return Result.Err("取り込みの位置を読めません: ${read.error.message}")
                is Result.Ok -> read.value?.let(::SourcePosition)
            }
        return when (val skipped = runBlocking { koin.get<KafkaIngestPositions>().skipLost(stored) }) {
            is Result.Err -> Result.Err("Kafka のオフセットを読めません: ${skipped.error.message}")
            is Result.Ok -> Result.Ok(skipped.value)
        }
    }

    private fun apply(
        store: JdbcBatchStore,
        next: SourcePosition,
        lost: Map<TopicPartition, Long>,
        confirm: Boolean,
    ): Int {
        lost.forEach { (partition, count) -> logger.warn("{} の {} 件は保持期間を過ぎて消えています", partition, count) }
        return when {
            lost.isEmpty() -> {
                BatchEtlCommands.OK.also { logger.info("消えたイベントはありません") }
            }

            !confirm -> {
                BatchEtlCommands.OK.also { logger.info("表示だけです。位置を書き換えるには --confirm を付けてください") }
            }

            else -> {
                when (val written = store.writeCheckpoint(JdbcRawOrderStore.WATERMARK, next.value, Clock.System.now())) {
                    is Result.Ok -> BatchEtlCommands.OK.also { logger.warn("消えたイベントを飛ばしました。週次の照合で、影響した日を確かめてください") }
                    is Result.Err -> BatchEtlCommands.FAILED.also { logger.error("取り込みの位置を書けません: {}", written.error.message) }
                }
            }
        }
    }
}
