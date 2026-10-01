package io.eia.platform.api.idempotency

import java.security.MessageDigest

/**
 * 要求の指紋(SHA-256 の 16 進)。同じ `Idempotency-Key` の再送が、最初と同じ要求かを判定する(ADR-0022 §3)。
 *
 * 入力は、メソッド・パス(クエリを含む)・本文を正規化したもの。
 * - 本文は [CanonicalBody] で正規化する(JSON はキーを並べ替えて空白を除く。キーの順序や空白が違うだけの再送を、同じ要求と
 *   みなすため)。監査の記録の本文の SHA-256 も同じ正規化を使う(ADR-0017 §1)。
 */
@JvmInline
public value class RequestFingerprint(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        public fun of(
            method: String,
            pathAndQuery: String,
            contentType: String?,
            body: ByteArray,
        ): RequestFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            // 区切りの 0x00 は、メソッドとパスに現れないため、境界をずらした別の要求と同じ入力にならない
            digest.update(method.uppercase().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(pathAndQuery.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(CanonicalBody.bytes(contentType, body))
            return RequestFingerprint(digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
