package io.eia.shared.kernel.money

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.ok

/**
 * [weights] の比で按分する。合計は必ず元の金額と一致する(ADR-0011)。
 *
 * 端数の配り方(最大剰余法): 各配分を 0 方向に切り捨てたうえで、残った最小通貨単位を
 * 切り捨てた剰余の大きい順に 1 単位ずつ配る。剰余が同じなら [weights] の先頭側を優先する。
 * 重み 0 の相手には常に 0 を配る。
 */
public fun Money.allocate(weights: List<Long>): Result<List<Money>, MoneyError> =
    totalWeight(weights).flatMap { total -> distribute(this, weights, total) }

/** [parts] 個に均等按分する。端数は先頭から 1 単位ずつ配る。 */
public fun Money.allocateEvenly(parts: Int): Result<List<Money>, MoneyError> =
    if (parts <= 0) err(MoneyError.InvalidAllocation("按分数は 1 以上です: $parts")) else allocate(List(parts) { 1L })

private fun totalWeight(weights: List<Long>): Result<Long, MoneyError> {
    val total = weights.fold(0L as Long?) { acc, weight -> acc?.plusExactOrNull(weight) }
    return when {
        weights.isEmpty() -> err(MoneyError.InvalidAllocation("重みが空です"))
        weights.any { it < 0 } -> err(MoneyError.InvalidAllocation("重みは 0 以上です: $weights"))
        total == null -> err(MoneyError.Overflow("sum($weights)"))
        total == 0L -> err(MoneyError.InvalidAllocation("重みの合計が 0 です"))
        else -> ok(total)
    }
}

private fun distribute(
    money: Money,
    weights: List<Long>,
    total: Long,
): Result<List<Money>, MoneyError> {
    val products = weights.mapNotNull { money.minorUnits.timesExactOrNull(it) }
    if (products.size != weights.size) return err(MoneyError.Overflow("$money * $weights"))
    val shares = products.map { it / total }.toMutableList()
    val remainders = products.map { (it % total).let { r -> if (r < 0) -r else r } }
    // 切り捨てた分の合計。|leftover| は相手の数より小さい
    val leftover = money.minorUnits - shares.sum()
    val step = if (leftover < 0) -1L else 1L
    remainders.indices
        .sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
        .take(if (leftover < 0) (-leftover).toInt() else leftover.toInt())
        .forEach { shares[it] += step }
    return ok(shares.map { Money.ofMinor(it, money.currency) })
}
