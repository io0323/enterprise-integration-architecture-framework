@file:Suppress("MagicNumber") // 状態コード・待ち時間

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.Client
import io.eia.tests.e2e.E2eEnvironment.GATEWAY
import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.json
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.eia.tests.e2e.E2eEnvironment.token
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.http.HttpRequest
import java.time.Duration

private const val STOREFRONT = "$GATEWAY/storefront/v1/graphql"
private val CALLER_SCOPES = setOf("bff-graphql-audience", "sales.order:read")

private fun readMissingOrderMillis(): Long {
    val started = System.nanoTime()
    sendRespectingRateLimit(request("$ORDERS/ord-none").GET()).statusCode() shouldBe 404
    return Duration.ofNanos(System.nanoTime() - started).toMillis()
}

/** `{ order(id:) { id } }` の最初のエラーの `extensions.code` と、サービス名。エラーがなければ null。 */
private fun storefrontError(): Pair<String, String>? {
    val response =
        sendRespectingRateLimit(
            request(STOREFRONT, client = null)
                .header("Authorization", "Bearer ${token(Client.A, CALLER_SCOPES)}")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        buildJsonObject { put("query", """{ order(id: "ord-none") { id } }""") }.toString(),
                    ),
                ),
        )
    response.statusCode() shouldBe 200
    val extensions =
        json(response.body())
            .jsonObject["errors"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.getValue("extensions")
            ?.jsonObject ?: return null
    return extensions.getValue("code").jsonPrimitive.content to extensions.getValue("service").jsonPrimitive.content
}

/**
 * 障害の注入の経路(ADR-0044)。E2E の基盤で、サービスの通信が実際に Toxiproxy を通っていることを確かめる。
 * 通っていなければ、③ の障害のシナリオは何も確かめていないことになる。障害のときの振る舞いは `ResilienceE2E` で確かめる。
 */
class FaultInjectionE2E :
    FunSpec({
        afterEach { Toxiproxy.reset() }

        test("order-service → PostgreSQL は Toxiproxy を通る(遅らせると API が遅くなる。戻すと速くなる)") {
            val delayed = Toxiproxy.whileDelayed(Toxiproxy.ORDER_DB, 2_000) { readMissingOrderMillis() }
            withClue("遅らせている間の応答時間") { (delayed >= 2_000) shouldBe true }
            val normal =
                eventually(Duration.ofSeconds(30), Duration.ofSeconds(1)) {
                    readMissingOrderMillis().takeIf { it < 2_000 }
                }
            withClue("戻した後の応答時間") { normal.shouldNotBeNull() }
        }

        test("bff-graphql → Gateway は Toxiproxy を通る(切ると注文の項目が UPSTREAM_UNAVAILABLE になる。戻すと読める)") {
            withClue("切る前") { eventually { "ok".takeIf { storefrontError() == null } }.shouldNotBeNull() }
            Toxiproxy.whileCut(Toxiproxy.BFF_GATEWAY) {
                storefrontError() shouldBe ("UPSTREAM_UNAVAILABLE" to "orders")
            }
            withClue("戻した後(Circuit Breaker の Half-Open を待つ)") {
                eventually { "ok".takeIf { storefrontError() == null } }.shouldNotBeNull()
            }
        }
    })
