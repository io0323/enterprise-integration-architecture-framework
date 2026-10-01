package io.eia.shared.canonical.common

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.kernel.truncatedToMicros
import kotlin.time.Instant

private const val MICROS_PER_SECOND = 1_000_000L
private const val NANOS_PER_MICRO = 1_000L

/**
 * Avro の `timestamp-micros`(UTC エポックからのマイクロ秒)に変換する(ADR-0012)。
 *
 * マイクロ秒未満は [truncatedToMicros](shared/kernel)で**切り捨てる**。切り捨ては時間軸の過去方向(負の無限大方向)で、エポック以前の時刻でも同じ。
 * `Long` のマイクロ秒で表せない時刻(約 ±29 万年を超える)はエラーにする。
 */
public fun Instant.toEpochMicros(): Result<Long, ValidationError> {
    // 切り捨てた後の秒の端数は、ちょうどマイクロ秒の倍数になる。
    // 負の側は epochSeconds * 10^6 だけが先に桁あふれするため、(epochSeconds + 1) * 10^6 - (10^6 - micros) で計算する。
    val truncated = truncatedToMicros()
    val micros = truncated.nanosecondsOfSecond / NANOS_PER_MICRO
    val result =
        if (truncated.epochSeconds >= 0) {
            truncated.epochSeconds.secondsToMicrosOrNull()?.plusExactOrNull(micros)
        } else {
            (truncated.epochSeconds + 1).secondsToMicrosOrNull()?.plusExactOrNull(micros - MICROS_PER_SECOND)
        }
    return result?.let { ok(it) } ?: err(ValidationError.of("timestamp", "timestamp-micros の範囲外です"))
}

/** Avro の `timestamp-micros` から復元する。[toEpochMicros] の逆変換で、全ての `Long` を表せる。 */
public fun instantOfEpochMicros(micros: Long): Instant =
    Instant.fromEpochSeconds(micros.floorDiv(MICROS_PER_SECOND), micros.mod(MICROS_PER_SECOND) * NANOS_PER_MICRO)

private fun Long.secondsToMicrosOrNull(): Long? =
    if (this > Long.MAX_VALUE / MICROS_PER_SECOND || this < Long.MIN_VALUE / MICROS_PER_SECOND) null else this * MICROS_PER_SECOND

private fun Long.plusExactOrNull(other: Long): Long? {
    val sum = this + other
    return if ((this xor sum) and (other xor sum) < 0) null else sum
}
