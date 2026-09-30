package io.eia.platform.api.idempotency

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * 要求の指紋(SHA-256 の 16 進)。同じ `Idempotency-Key` の再送が、最初と同じ要求かを判定する(ADR-0022 §3)。
 *
 * 入力は、メソッド・パス(クエリを含む)・本文を正規化したもの。
 * - JSON の本文(`application/json` か `+json`)は、オブジェクトのキーを並べ替え、空白を除いた形にする。キーの順序や空白が違うだけの
 *   再送を、同じ要求とみなすため。数値は書かれた文字列のまま扱う(`1` と `1.0` は別の要求)。
 * - JSON として読めない本文と、JSON でない本文は、バイト列のまま使う。
 */
@JvmInline
public value class RequestFingerprint(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        private val json = Json

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
            digest.update(normalizedBody(contentType, body))
            return RequestFingerprint(digest.digest().joinToString("") { "%02x".format(it) })
        }

        private fun normalizedBody(
            contentType: String?,
            body: ByteArray,
        ): ByteArray {
            val element = if (isJson(contentType)) parseJson(body) else null
            return element?.let { sorted(it).toString().toByteArray(Charsets.UTF_8) } ?: body
        }

        /** JSON として読めなければ `null`(UTF-8 として不正なバイト列を含む)。 */
        private fun parseJson(body: ByteArray): JsonElement? =
            try {
                json.parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true))
            } catch (_: SerializationException) {
                null
            } catch (_: CharacterCodingException) {
                null
            }

        private fun isJson(contentType: String?): Boolean {
            val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
            return mediaType == "application/json" || mediaType.endsWith("+json")
        }

        private fun sorted(element: JsonElement): JsonElement =
            when (element) {
                is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
                is JsonArray -> JsonArray(element.map(::sorted))
                else -> element
            }
    }
}
