package io.eia.platform.api.idempotency

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest

/**
 * 要求の本文の正規化。冪等の指紋([RequestFingerprint]。ADR-0022 §3)と、監査の記録の本文の SHA-256(ADR-0017 §1)で共有する。
 * 同じ本文の見た目の違い(空白・キーの順序)で、値が変わらないようにする。
 *
 * - JSON の本文(`application/json` か `+json`)は、オブジェクトのキーを並べ替え、空白を除いた形にする。数値は書かれた文字列の
 *   まま扱う(`1` と `1.0` は別の本文)。
 * - JSON として読めない本文と、JSON でない本文は、バイト列のまま使う。
 */
public object CanonicalBody {
    private val json = Json

    /** 正規化したバイト列。 */
    public fun bytes(
        contentType: String?,
        body: ByteArray,
    ): ByteArray {
        val element = if (isJson(contentType)) parseJson(body) else null
        return element?.let { sorted(it).toString().toByteArray(Charsets.UTF_8) } ?: body
    }

    /** 正規化したバイト列の SHA-256(小文字の 16 進 64 文字)。 */
    public fun sha256Hex(
        contentType: String?,
        body: ByteArray,
    ): String = MessageDigest.getInstance("SHA-256").digest(bytes(contentType, body)).joinToString("") { "%02x".format(it) }

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
