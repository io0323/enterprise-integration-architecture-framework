package io.eia.shared.resilience.trace

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import kotlin.jvm.JvmInline
import kotlin.random.Random

/** W3C Trace Context の trace-id(16 バイト。小文字 16 進 32 文字)。全 0 は無効。 */
@JvmInline
public value class TraceId private constructor(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        public const val HEX_LENGTH: Int = 32

        public fun parse(value: String): Result<TraceId, ValidationError> = parseHexId(value, HEX_LENGTH, "traceId").map(::TraceId)

        /** [random] から生成する。全 0 にはならない。 */
        public fun generate(random: Random = Random.Default): TraceId = TraceId(randomHexId(random, HEX_LENGTH / 2))
    }
}

/** W3C Trace Context の parent-id / span-id(8 バイト。小文字 16 進 16 文字)。全 0 は無効。 */
@JvmInline
public value class SpanId private constructor(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        public const val HEX_LENGTH: Int = 16

        public fun parse(value: String): Result<SpanId, ValidationError> = parseHexId(value, HEX_LENGTH, "spanId").map(::SpanId)

        /** [random] から生成する。全 0 にはならない。 */
        public fun generate(random: Random = Random.Default): SpanId = SpanId(randomHexId(random, HEX_LENGTH / 2))
    }
}

/**
 * W3C Trace Context の trace-flags。bit 0 が sampled、bit 1 が random(Level 2)。
 * kernel の値オブジェクトと同じく、外部の値は [of] で検証してから作る(ADR-0011)。
 */
@JvmInline
public value class TraceFlags internal constructor(
    public val value: Int,
) {
    public val sampled: Boolean get() = value and SAMPLED_BIT != 0

    public fun withSampled(sampled: Boolean): TraceFlags = TraceFlags(if (sampled) value or SAMPLED_BIT else value and SAMPLED_BIT.inv())

    override fun toString(): String = value.toString(HEX_RADIX).padStart(2, '0')

    public companion object {
        private const val MAX = 0xff
        private const val SAMPLED_BIT = 0x01
        public val SAMPLED: TraceFlags = TraceFlags(SAMPLED_BIT)
        public val NONE: TraceFlags = TraceFlags(0)

        /** 1 バイト(0〜255)の値から作る。範囲外は検証エラー。 */
        public fun of(value: Int): Result<TraceFlags, ValidationError> =
            if (value in 0..MAX) ok(TraceFlags(value)) else err(ValidationError.of("traceFlags", "0〜255 です"))
    }
}

internal const val HEX_RADIX = 16
private val LOWER_HEX = Regex("^[0-9a-f]+$")

private fun parseHexId(
    value: String,
    length: Int,
    field: String,
): Result<String, ValidationError> =
    when {
        value.length != length || !LOWER_HEX.matches(value) -> err(ValidationError.of(field, "小文字の 16 進 $length 文字です"))
        value.all { it == '0' } -> err(ValidationError.of(field, "全 0 は無効です"))
        else -> ok(value)
    }

private fun randomHexId(
    random: Random,
    bytes: Int,
): String {
    while (true) {
        val id = random.nextBytes(bytes).toLowerHex()
        if (id.any { it != '0' }) return id
    }
}

@Suppress("MagicNumber") // 1 バイトを 16 進 2 文字にする
internal fun ByteArray.toLowerHex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(HEX_RADIX).padStart(2, '0') }
