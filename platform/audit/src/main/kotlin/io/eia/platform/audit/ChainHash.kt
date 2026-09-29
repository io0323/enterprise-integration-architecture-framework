package io.eia.platform.audit

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.security.MessageDigest

/** SHA-256 のハッシュ値(小文字の 16 進数 64 桁)。チェーンの `prev_hash` / `hash` とペイロードのハッシュに使う(ADR-0017)。 */
@JvmInline
public value class ChainHash private constructor(
    public val hex: String,
) {
    override fun toString(): String = hex

    public companion object {
        public const val HEX_LENGTH: Int = 64

        /** チェーンの先頭の記録の `prev_hash`(64 個の 0)。 */
        public val GENESIS: ChainHash = ChainHash("0".repeat(HEX_LENGTH))

        private val FORMAT = Regex("^[0-9a-f]{$HEX_LENGTH}$")

        public fun parse(hex: String): Result<ChainHash, ValidationError> =
            if (FORMAT.matches(hex)) {
                ok(ChainHash(hex))
            } else {
                err(ValidationError.of("hash", "小文字の 16 進数 $HEX_LENGTH 桁ではありません"))
            }

        /** [bytes] の SHA-256。 */
        public fun sha256(bytes: ByteArray): ChainHash = ChainHash(toHex(MessageDigest.getInstance("SHA-256").digest(bytes)))

        private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
