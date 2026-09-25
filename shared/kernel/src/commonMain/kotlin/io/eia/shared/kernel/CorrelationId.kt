package io.eia.shared.kernel

import kotlin.jvm.JvmInline
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * 業務トランザクション ID(`X-Correlation-Id` / `correlationid`)。入口で採番し、全チャネルで伝搬する(Framework 14)。
 *
 * 受信した値は [parse] で検証してから使う。ログやヘッダに載るため、使える文字を制限している。
 */
@JvmInline
public value class CorrelationId private constructor(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        public const val MAX_LENGTH: Int = 128
        private val ALLOWED = Regex("^[A-Za-z0-9._-]+$")

        public fun parse(value: String): Result<CorrelationId, ValidationError> =
            when {
                value.isEmpty() || value.length > MAX_LENGTH -> {
                    err(ValidationError.of("correlationId", "長さは 1〜$MAX_LENGTH 文字です"))
                }

                !ALLOWED.matches(value) -> {
                    err(ValidationError.of("correlationId", "使える文字は英数字と . _ - のみです"))
                }

                else -> {
                    ok(CorrelationId(value))
                }
            }

        /** UUIDv4 形式の ID を生成する。 */
        public fun generate(): CorrelationId = CorrelationId(Uuid.random().toString())

        /** [random] から UUIDv4 形式の ID を生成する。テストで値を固定するときに使う。 */
        public fun generate(random: Random): CorrelationId = CorrelationId(randomUuidV4(random))
    }
}

@Suppress("MagicNumber") // RFC 9562 のバージョン・バリアントのビット位置
internal fun randomUuidV4(random: Random): String {
    val bytes = random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    return Uuid.fromByteArray(bytes).toString()
}
