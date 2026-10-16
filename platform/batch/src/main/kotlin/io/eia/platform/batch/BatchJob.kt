package io.eia.platform.batch

import io.eia.platform.filetransfer.store.ObjectReader

/**
 * DAG の起動の条件(Framework 7.2: 時刻より、前段の完了・ファイルの到着での起動を優先する)。
 */
public sealed interface Trigger {
    /** 時刻での起動。 */
    public data class Scheduled(
        val schedule: Schedule,
    ) : Trigger

    /**
     * 前段の DAG([upstream])の同じパーティションが成功したら起動する。前段がやり直し([RunMode.RESTART])で作り直したら、
     * こちらもやり直す(前段の世代を実行の属性 `upstream.generation` に残して比べる)。
     */
    public data class AfterDag(
        val upstream: DagId,
    ) : Trigger

    /**
     * [source] の [prefix] の下に `.manifest.json` が届いたら起動する(Framework 9.2: マニフェストの到着を完了の合図にする)。
     * [source] は platform/file-transfer の置き場(読み取りの権限だけを持つ identity で作る。ADR-0032 §1)。
     * パーティションはマニフェストの名前から `.manifest.json` を除いたもの(データファイルの拡張子を除いた名前と同じ)。
     */
    public data class FileArrival(
        val source: ObjectReader,
        val prefix: String,
        /**
         * true(既定)なら、データファイルがない・大きさが違うマニフェストでは起動せず、ログに残す。
         * false なら、マニフェストの形式が正しければ起動し、欠損・大きさの違いの判定を DAG に任せる
         * (受け手が欠損を不合格にして quarantine に送るとき。マニフェストは最後に置く完了の合図なので、データがないのは欠陥。ADR-0032 §3)。
         */
        val requireDataFile: Boolean = true,
    ) : Trigger {
        init {
            require(prefix.isEmpty() || prefix.endsWith("/")) { "prefix は空か / で終えてください: $prefix" }
        }
    }
}

/** DAG と、その起動の条件。 */
public data class BatchJob(
    val dag: DagDefinition,
    val trigger: Trigger,
)

/** [jobs] を、前段の DAG が先に来る順に並べる。循環していれば例外。 */
internal fun upstreamFirst(jobs: List<BatchJob>): List<BatchJob> {
    val byId = jobs.associateBy { it.dag.id }
    require(byId.size == jobs.size) { "DAG の ID が重複しています" }
    val ordered = mutableListOf<BatchJob>()
    val visiting = mutableSetOf<DagId>()

    fun visit(job: BatchJob) {
        if (job in ordered) return
        require(visiting.add(job.dag.id)) { "前段の DAG が循環しています: ${job.dag.id}" }
        (job.trigger as? Trigger.AfterDag)?.let { byId[it.upstream] }?.let(::visit)
        visiting.remove(job.dag.id)
        ordered += job
    }
    jobs.forEach(::visit)
    return ordered
}
