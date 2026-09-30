package io.eia.platform.api.idempotency

/**
 * 処理の結果の HTTP 応答(状態コード・ヘッダ・本文)。[IdempotencyHandler] に渡す処理は、応答をこの形で返す。
 * `Content-Type` はヘッダに入れる。
 */
public class HttpSnapshot(
    public val status: Int,
    public val headers: List<Pair<String, String>>,
    public val body: ByteArray,
) {
    /** ヘッダの値(名前は大文字小文字を区別しない)。 */
    public fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    override fun toString(): String = "HttpSnapshot(status=$status, headers=${headers.map { it.first }}, body=${body.size} bytes)"
}

/**
 * 保存した応答。再送には、これをそのまま返す(ADR-0022 §3)。ヘッダは許可したもの([IdempotencyConfig.storedHeaders])だけ。
 * `X-Correlation-Id`・`traceparent`・`Set-Cookie`・`Date` などは保存しない(再送への応答には、その要求自身の値が付く)。
 */
public class StoredResponse(
    public val status: Int,
    public val headers: List<Pair<String, String>>,
    public val body: ByteArray,
) {
    /** ヘッダの値(名前は大文字小文字を区別しない)。 */
    public fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    override fun equals(other: Any?): Boolean =
        other is StoredResponse && status == other.status && headers == other.headers && body.contentEquals(other.body)

    override fun hashCode(): Int = (status * HASH_PRIME + headers.hashCode()) * HASH_PRIME + body.contentHashCode()

    override fun toString(): String = "StoredResponse(status=$status, headers=${headers.map { it.first }}, body=${body.size} bytes)"

    private companion object {
        const val HASH_PRIME = 31
    }
}
