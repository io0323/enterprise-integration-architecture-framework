package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.TEMPO
import io.eia.tests.e2e.E2eEnvironment.get
import io.eia.tests.e2e.E2eEnvironment.json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.HexFormat

/**
 * Tempo の trace の span(サービス名・span ID・親の span ID・名前・種類)。ID は base64 で返るので 16 進にする。
 * 種類は OTLP の JSON の名前(`SPAN_KIND_CONSUMER` など)。
 */
internal data class SpanInfo(
    val service: String,
    val spanId: String,
    val parentSpanId: String,
    val name: String,
    val kind: String,
)

/** Tempo から [traceId] の span をすべて取る(まだ届いていなければ空。Tempo は JSON ではない「trace not found」を返す)。 */
internal fun spans(traceId: String): List<SpanInfo> {
    val trace =
        try {
            json(get("$TEMPO/api/traces/$traceId")).jsonObject
        } catch (_: SerializationException) {
            return emptyList()
        }
    val hex = { id: String -> if (id.isEmpty()) "" else HexFormat.of().formatHex(Base64.getDecoder().decode(id)) }
    return trace["batches"]?.jsonArray.orEmpty().flatMap { batch ->
        val service =
            batch.jsonObject
                .getValue("resource")
                .jsonObject["attributes"]
                ?.jsonArray
                .orEmpty()
                .map { it.jsonObject }
                .first { it.getValue("key").jsonPrimitive.content == "service.name" }
                .getValue("value")
                .jsonObject
                .getValue("stringValue")
                .jsonPrimitive.content
        batch.jsonObject["scopeSpans"]?.jsonArray.orEmpty().flatMap { scope ->
            scope.jsonObject["spans"]?.jsonArray.orEmpty().map { span ->
                val s = span.jsonObject
                SpanInfo(service, hex(s.text("spanId")), hex(s.text("parentSpanId")), s.text("name"), s.text("kind"))
            }
        }
    }
}

internal fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
