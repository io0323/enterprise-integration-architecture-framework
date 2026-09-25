package io.eia.shared.kernel.money

// Math.addExact / multiplyExact は JVM 専用のため、common で同等の検査を行う。オーバーフロー時は null。

internal fun Long.plusExactOrNull(other: Long): Long? {
    val sum = this + other
    // 同符号どうしの加算で符号が変わったらオーバーフロー
    return if ((this xor sum) and (other xor sum) < 0) null else sum
}

internal fun Long.timesExactOrNull(other: Long): Long? {
    val product = this * other
    // Long.MIN_VALUE * -1 は、積を割り戻しても元の値に戻るため個別に判定する
    val minTimesMinusOne = this == Long.MIN_VALUE && other == -1L
    val overflow = other != 0L && (product / other != this || minTimesMinusOne)
    return if (overflow) null else product
}
