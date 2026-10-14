package io.eia.batchetl.domain

/**
 * 1 日の注文の集計(件数とハッシュ。ADR-0031 §3)。order-service の `GET /v1/order-summaries/daily/{date}` と、分析の日次のパーティションの
 * 両方から同じ形で作り、比べる。
 */
public data class DailySummary(
    val day: BusinessDay,
    val orderCount: Long,
    val digest: String,
)

/**
 * 日次の集計のハッシュの 1 行(契約 `GET /v1/order-summaries/daily/{date}` の digest。ADR-0031 §3)。order-service と同じ形。
 * 行を注文 ID のバイト順(UTF-8)に並べて `\n` でつなぎ(最後の行の後には付けない)、SHA-256 を小文字の 16 進にする
 * (ハッシュの計算は adapters。domain は形式だけを持つ)。
 */
public object DailyOrderDigest {
    public const val LINE_SEPARATOR: String = "\n"

    public fun line(
        orderId: String,
        status: OrderStatus,
        totalMinorUnits: Long,
        currency: String,
    ): String = "$orderId\t${status.name}\t$totalMinorUnits\t$currency"
}

/** 1 日の照合の結果。 */
public enum class ReconcileOutcome {
    /** 件数とハッシュが一致した。 */
    MATCH,

    /** 比べ直しても一致しない(取りこぼし・重複・状態のずれ)。 */
    DRIFT,

    /** 比べられなかった(パーティションがまだない・order-service に問い合わせられない)。 */
    UNVERIFIED,
}

/**
 * 1 日の照合。
 *
 * @property source order-service の集計。問い合わせられなければ null
 * @property mart 分析のパーティションの集計。パーティションがまだなければ null
 */
public data class DayReconciliation(
    val day: BusinessDay,
    val source: DailySummary?,
    val mart: DailySummary?,
) {
    val outcome: ReconcileOutcome
        get() =
            when {
                source == null || mart == null -> ReconcileOutcome.UNVERIFIED
                source.orderCount == mart.orderCount && source.digest == mart.digest -> ReconcileOutcome.MATCH
                else -> ReconcileOutcome.DRIFT
            }
}

/**
 * 週次の Full 照合の範囲(ADR-0031 §6)。実行の日の前日から [DAYS] 日前まで(今日は終わっていないので含めない)。
 *
 * [DAYS] は、遅れて届いたデータを自動で作り直す範囲([LateDataPolicy.REBUILD_WINDOW_DAYS] = 31 日)より 1 週間長い。
 * 自動で作り直さなくなった直後の週も 1 回は比べ、作り直しの範囲の外で起きたずれを見逃さない。
 */
public object ReconcilePolicy {
    public const val DAYS: Int = LateDataPolicy.REBUILD_WINDOW_DAYS + 7

    /** 照合する日(古い順)。 */
    public fun days(runDay: BusinessDay): List<BusinessDay> = (DAYS downTo 1).map { runDay.minusDays(it) }
}
