package io.eia.platform.security.token

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** トークンエンドポイントの応答(RFC 6749 §5.1・§5.2)の解析。 */
internal object TokenResponse {
    /** 応答の本文の大きさの上限。これを超える応答は解析しない。 */
    const val MAX_BODY_BYTES: Int = 64 * 1024

    private val OAUTH_ERROR_CODE = Regex("[a-z_]{1,64}")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 成功の応答を [AccessToken] にする。期限は [requestedAt](要求を送る前の時刻)+ `expires_in`。
     * `scope` がなければ [requestedScopes] を使う(RFC 6749 §5.1)。
     */
    @Suppress("ReturnCount") // 検証の段ごとに、最初の失敗で返す
    fun parse(
        body: ByteArray,
        requestedAt: Instant,
        requestedScopes: Set<String>,
    ): Result<AccessToken, TokenError> {
        if (body.size > MAX_BODY_BYTES) return err(InvalidTokenResponse("body_too_large"))
        val json = parseObject(body) ?: return err(InvalidTokenResponse("not_json_object"))
        val accessToken =
            json.string("access_token")?.takeIf { it.isNotEmpty() } ?: return err(InvalidTokenResponse("missing_access_token"))
        val expiresIn = json["expires_in"]
        val expiresInSeconds = (expiresIn as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val invalid =
            when {
                !json.string("token_type").equals("Bearer", ignoreCase = true) -> "not_bearer"
                expiresIn != null && (expiresInSeconds == null || expiresInSeconds <= 0) -> "invalid_expires_in"
                else -> null
            }
        if (invalid != null) return err(InvalidTokenResponse(invalid))
        val scopes =
            json
                .string("scope")
                ?.split(' ')
                ?.filter(String::isNotEmpty)
                ?.toSet()
                ?: requestedScopes
        return ok(AccessToken(accessToken, expiresInSeconds?.let { requestedAt + it.seconds }, scopes))
    }

    /** エラーの応答の `error`(RFC 6749 §5.2)。形式が想定外(英小文字と `_` 以外を含む・長すぎる)なら null にする(本文の値をログに流さない)。 */
    fun oauthErrorCode(body: ByteArray): String? =
        parseObject(body)
            ?.string("error")
            ?.takeIf { OAUTH_ERROR_CODE.matches(it) }

    private fun parseObject(body: ByteArray): JsonObject? =
        if (body.size > MAX_BODY_BYTES) {
            null
        } else {
            try {
                json.parseToJsonElement(body.decodeToString()) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
