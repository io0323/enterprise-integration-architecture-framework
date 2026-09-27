package io.eia.platform.security.token

import kotlin.time.Instant

/**
 * 取得したアクセストークン。[toString] は値を伏せる(ログ・例外に入れても値は出ない)。
 *
 * @param expiresAt 期限(要求を送った時刻 + `expires_in`。受信までの時間の分だけ実際より早めに見積もる)。
 *   応答に `expires_in` がなければ null で、その場合はキャッシュしない
 * @param scopes 応答の `scope`。応答になければ要求したスコープ(RFC 6749 §5.1)
 */
public class AccessToken(
    private val value: String,
    public val expiresAt: Instant?,
    public val scopes: Set<String>,
) {
    /** トークンの値。`Authorization` のヘッダに入れる直前にだけ使う。 */
    public fun reveal(): String = value

    /** `Authorization` のヘッダの値(`Bearer <token>`)。 */
    public fun authorizationHeader(): String = "Bearer $value"

    override fun toString(): String = "AccessToken(***, expiresAt=$expiresAt, scopes=$scopes)"
}
