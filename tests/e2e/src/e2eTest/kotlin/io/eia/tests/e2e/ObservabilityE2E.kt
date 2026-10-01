@file:Suppress("MagicNumber") // HTTP の状態コード・待ち時間・件数

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.GRAFANA
import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.PROMETHEUS
import io.eia.tests.e2e.E2eEnvironment.TEMPO
import io.eia.tests.e2e.E2eEnvironment.basic
import io.eia.tests.e2e.E2eEnvironment.encode
import io.eia.tests.e2e.E2eEnvironment.get
import io.eia.tests.e2e.E2eEnvironment.json
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.secret
import io.eia.tests.e2e.E2eEnvironment.send
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpRequest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

private fun randomHex(bytes: Int): String = HexFormat.of().formatHex(ByteArray(bytes).also(SecureRandom()::nextBytes))

/** Tempo の trace の span(サービス名・span ID・親の span ID)。ID は base64 で返るので 16 進にする。 */
private data class SpanInfo(
    val service: String,
    val spanId: String,
    val parentSpanId: String,
)

private fun spans(traceId: String): List<SpanInfo> {
    val trace = json(get("$TEMPO/api/traces/$traceId")).jsonObject
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
                SpanInfo(service, hex(s.text("spanId")), hex(s.text("parentSpanId")))
            }
        }
    }
}

private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

/** Prometheus の即時の問い合わせの結果の件数(式の誤りは例外)。 */
private fun series(expr: String): Int {
    val response = json(get("$PROMETHEUS/api/v1/query?query=${encode(expr)}")).jsonObject
    check(response.getValue("status").jsonPrimitive.content == "success") { "式のエラー: $expr" }
    return response
        .getValue("data")
        .jsonObject
        .getValue("result")
        .jsonArray.size
}

private const val ORDER_BODY =
    """{"customerId":"cust-e2e",""" +
        """"lines":[{"productId":"prod-1","sku":"SKU-1","quantity":1,"unitPrice":{"amount":"100","currency":"JPY"}}],""" +
        """"shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"Chiyoda","line1":"1-1"}}"""

/** ROADMAP P05 の DoD「トレースが Tempo で、RED がダッシュボードで確認できる」。 */
class ObservabilityE2E :
    FunSpec({
        test("外部の traceparent は使われず、Tempo で apisix の span が起点になり、order-service の span の親になる(ADR-0023 §4)") {
            val sentTraceId = randomHex(16)
            val correlationId = "e2e-trace-${UUID.randomUUID()}"
            val response =
                sendRespectingRateLimit(
                    request("$ORDERS/ord-none")
                        .header("traceparent", "00-$sentTraceId-${randomHex(8)}-01")
                        .header("X-Correlation-Id", correlationId)
                        .GET(),
                )
            response.statusCode() shouldBe 404
            response.header("X-Correlation-Id") shouldBe correlationId

            val query = """{ resource.service.name = "apisix" && span.x-correlation-id = "$correlationId" }"""
            val traceId =
                eventually {
                    json(get("$TEMPO/api/search?limit=5&q=${encode(query)}"))
                        .jsonObject["traces"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.text("traceID")
                }.shouldNotBeNull()
            traceId shouldNotBe sentTraceId

            val spans = eventually { spans(traceId).takeIf { all -> all.any { it.service == "order-service" } } }.shouldNotBeNull()
            val root = spans.single { it.service == "apisix" && it.parentSpanId.isEmpty() }
            spans.filter { it.service == "order-service" && it.parentSpanId == root.spanId } shouldNotBe emptyList<SpanInfo>()
        }

        test("RED のダッシュボード(Order API — RED)の order-service と Gateway のパネルにデータが出る") {
            repeat(5) { sendRespectingRateLimit(request("$ORDERS/ord-none").GET()) }
            // 監査のパネルのデータ(注文の受け付けを 1 件)
            sendRespectingRateLimit(
                request(ORDERS)
                    .header("Idempotency-Key", "e2e-red-${UUID.randomUUID()}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(ORDER_BODY)),
            ).statusCode() shouldBe 201

            val dashboard =
                send(
                    HttpRequest
                        .newBuilder(URI.create("$GRAFANA/api/dashboards/uid/eiaf-order-red"))
                        .header("Authorization", basic("admin", secret("GRAFANA_ADMIN_PASSWORD"))),
                )
            dashboard.statusCode() shouldBe 200
            val panels =
                json(dashboard.body())
                    .jsonObject
                    .getValue("dashboard")
                    .jsonObject
                    .getValue("panels")
                    .jsonArray
                    .map { it.jsonObject }
                    .filter { it.text("type") != "row" }

            // 依存先の呼び出しのパネルは P06・P07 まで空、エラーの種類別はエラーがなければ空
            val optional = listOf("呼び出し元", "依存先", "error.type 別", "監査の追記の失敗")
            val required = panels.filterNot { panel -> optional.any { panel.text("title").contains(it) } }
            val withoutData = {
                required
                    .flatMap { panel -> panel.getValue("targets").jsonArray.map { panel.text("title") to it.jsonObject.text("expr") } }
                    .filter { (_, expr) -> series(expr.replace("\$__rate_interval", "2m")) == 0 }
                    .map { it.first }
            }
            // メトリクスの送信(10 秒ごと)と scrape(15 秒ごと)を待つ
            (eventually { withoutData().takeIf { it.isEmpty() } } ?: withoutData()).shouldBeEmpty()
        }
    })
