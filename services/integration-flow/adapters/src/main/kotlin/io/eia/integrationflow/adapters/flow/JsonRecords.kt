package io.eia.integrationflow.adapters.flow

import io.eia.integrationflow.domain.SourceValue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.CharacterCodingException

/** 元のレコード(SaaS の JSON)を、domain の [SourceValue] にする。知らない項目もそのまま持つ(使うのは mapping に書いた項目だけ)。 */
public object JsonRecords {
    /** 本文(UTF-8 の JSON のオブジェクト)。UTF-8 でない・JSON のオブジェクトでなければ null(取り込みの側で DLQ に隔離する)。 */
    public fun parse(body: ByteArray): SourceValue? =
        try {
            (Json.parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true)) as? JsonObject)?.let(::of)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }

    public fun of(element: JsonElement): SourceValue =
        when (element) {
            JsonNull -> SourceValue.Null
            is JsonObject -> SourceValue.Obj(element.mapValues { of(it.value) })
            is JsonArray -> SourceValue.Arr(element.map(::of))
            is JsonPrimitive -> primitive(element)
        }

    private fun primitive(value: JsonPrimitive): SourceValue =
        when {
            value.isString -> SourceValue.Text(value.content)
            value.content == "true" || value.content == "false" -> SourceValue.Bool(value.content.toBoolean())
            else -> SourceValue.Number(value.content)
        }
}
