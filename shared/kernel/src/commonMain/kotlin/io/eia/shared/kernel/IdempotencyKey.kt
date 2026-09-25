package io.eia.shared.kernel

import kotlin.jvm.JvmInline
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * `Idempotency-Key` ヘッダの値(Framework 5.4)。POST では必須で、サーバは鍵ごとに結果を 24h 保持する。
 *
 * 受信した値は [parse] で検証する。空白と制御文字を含まない表示可能な ASCII(0x21〜0x7E)で 1〜[MAX_LENGTH] 文字。
 */
@JvmInline
public value class IdempotencyKey private constructor(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        public const val MAX_LENGTH: Int = 255
        private const val FIRST_VISIBLE_ASCII = '!'
        private const val LAST_VISIBLE_ASCII = '~'

        public fun parse(value: String): Result<IdempotencyKey, ValidationError> =
            when {
                value.isEmpty() || value.length > MAX_LENGTH -> {
                    err(ValidationError.of("idempotencyKey", "長さは 1〜$MAX_LENGTH 文字です"))
                }

                value.any { it !in FIRST_VISIBLE_ASCII..LAST_VISIBLE_ASCII } -> {
                    err(ValidationError.of("idempotencyKey", "表示可能な ASCII 文字(空白を除く)のみ使えます"))
                }

                else -> {
                    ok(IdempotencyKey(value))
                }
            }

        /** 呼出側(クライアント・SDK)がリクエストごとに採番するための UUIDv4。 */
        public fun generate(): IdempotencyKey = IdempotencyKey(Uuid.random().toString())

        public fun generate(random: Random): IdempotencyKey = IdempotencyKey(randomUuidV4(random))
    }
}
