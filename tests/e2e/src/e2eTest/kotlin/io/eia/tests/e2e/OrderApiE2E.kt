@file:Suppress("MagicNumber") // HTTP の状態コード・待ち時間・件数

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.json
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

private fun body(quantity: Int = 1) =
    """
    {"customerId":"cust-e2e",
     "lines":[{"productId":"prod-1","sku":"SKU-1","quantity":$quantity,"unitPrice":{"amount":"1200","currency":"JPY"}}],
     "shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"Chiyoda","line1":"1-1"}}
    """.trimIndent()

private fun place(
    key: String,
    payload: String = body(),
): HttpResponse<String> =
    sendRespectingRateLimit(
        request(ORDERS)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload)),
    )

/** ROADMAP P05 の DoD「同一 Idempotency-Key の再送で同一レスポンスが返る」(Gateway 経由。ADR-0022 §3)。 */
class OrderApiE2E :
    FunSpec({
        test("POST は 201。同じ Idempotency-Key の再送は、同じ状態コード・Location・本文と Idempotent-Replayed: true を返す") {
            val key = "e2e-${UUID.randomUUID()}"
            val created = place(key)
            created.statusCode() shouldBe 201
            created.header("Idempotent-Replayed") shouldBe null

            val replayed = place(key)
            replayed.statusCode() shouldBe 201
            replayed.header("Idempotent-Replayed") shouldBe "true"
            replayed.header("Location") shouldBe created.header("Location")
            replayed.body() shouldBe created.body()
            // 再送への応答の X-Correlation-Id は、その要求自身のもの(ADR-0022 §3)
            replayed.header("X-Correlation-Id") shouldNotBe created.header("X-Correlation-Id")
        }

        test("登録した注文を、公開パスの Location(相対参照)で読める") {
            val created = place("e2e-${UUID.randomUUID()}")
            val location = created.header("Location") ?: error("Location がありません")
            location shouldStartWith "orders/"

            val read = sendRespectingRateLimit(request("${E2eEnvironment.GATEWAY}/sales/v1/$location").GET())
            read.statusCode() shouldBe 200
            json(read.body()).jsonObject["id"] shouldBe json(created.body()).jsonObject["id"]
        }

        test("同じ Idempotency-Key で内容の違う要求は 422 idempotency-key-reused") {
            val key = "e2e-${UUID.randomUUID()}"
            place(key, body(quantity = 1)).statusCode() shouldBe 201
            val reused = place(key, body(quantity = 2))
            reused.statusCode() shouldBe 422
            json(reused.body())
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content shouldBe
                "https://eiaf.example/problems/idempotency-key-reused"
        }
    })
