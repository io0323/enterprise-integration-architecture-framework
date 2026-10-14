package io.eia.batchetl.domain

/**
 * 日次のパーティションを作った後に届いたデータ(遅れて届いたイベント・後からの状態の遷移)の扱い(ADR-0031 §5)。
 *
 * 取り込みで、データが変わった業務の日に印を付ける(作り直しが要る日)。[runDay] の日次の実行は、次の日を作り直す。
 * - [runDay] 自身(印がなくても作る。注文のない日も空のパーティションとして作る)。
 * - 印の付いた日のうち、[runDay] から [REBUILD_WINDOW_DAYS] 日前まで。
 *
 * それより古い日([RebuildPlan.tooOld])は自動では作り直さない(印は残し、メトリクスとログで知らせる。運用者が明示的に作り直す。
 * docs/runbooks/batch-rerun.md)。[runDay] より後の日(まだ終わっていない日の分)は、その日の実行に任せる。
 */
public object LateDataPolicy {
    /**
     * 自動で作り直す日数。注文の状態の遷移(出荷・配達・取消)は、受け付けからおおむね 1 か月以内に終わる。
     * 月次の締め(前月分)の前に、前月の全日が作り直されるように 31 日にする。
     */
    public const val REBUILD_WINDOW_DAYS: Int = 31

    /**
     * @property rebuild 作り直す日(古い順。[runDay] を含む)
     * @property tooOld 印が付いているが、自動では作り直さない日
     */
    public data class RebuildPlan(
        val rebuild: List<BusinessDay>,
        val tooOld: List<BusinessDay>,
    )

    public fun plan(
        runDay: BusinessDay,
        dirty: Collection<BusinessDay>,
    ): RebuildPlan {
        val oldest = runDay.minusDays(REBUILD_WINDOW_DAYS)
        val rebuild = (dirty.filter { it in oldest..runDay } + runDay).distinct().sorted()
        val tooOld = dirty.filter { it < oldest }.distinct().sorted()
        return RebuildPlan(rebuild, tooOld)
    }
}
