package io.eia.saasmock.app

import io.eia.platform.security.secret.EnvSecretProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SaasMockRoutesSpec :
    FunSpec({
        val env = mapOf("SAAS_MOCK_API_TOKEN" to "api-token", "SAAS_MOCK_ADMIN_TOKEN" to "admin-token")
        val start = Instant.parse("2026-10-21T00:00:00Z")

        fun mock(
            clock: MutableClock = MutableClock(start),
            limit: RateLimiter.Limit = RateLimiter.Limit(100, 1.seconds),
        ): SaasMock {
            val receiver = HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) })
            val dispatcher = WebhookDispatcher(receiver, "http://receiver", { emptyList() }, listOf(1.seconds), clock)
            return SaasMock(CrmCustomers(clock), RateLimiter(limit.requests, limit.per), dispatcher, EnvSecretProvider(env))
        }

        fun withMock(
            mock: SaasMock,
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application { saasMock(mock) }
            block()
        }

        test("一覧はカーソルでページングする。最後のページの next_cursor は null。null の住所の項目は書かない") {
            val mock = mock()
            SaasMockServer.seed(mock.customers, 5)
            withMock(mock) {
                val first = client.get("/v1/customers?limit=2") { bearerAuth("api-token") }
                first.status shouldBe HttpStatusCode.OK
                val page1 = Json.parseToJsonElement(first.bodyAsText()).jsonObject
                page1["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content } shouldBe
                    listOf("cus_seed_00001", "cus_seed_00002")
                val ids = mutableListOf<String>()
                var next: String? = page1["next_cursor"]!!.jsonPrimitive.content
                while (next != null) {
                    val response = client.get("/v1/customers?limit=2&cursor=$next") { bearerAuth("api-token") }
                    val page = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    ids += page["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                    next = page["next_cursor"]!!.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                }
                ids shouldBe listOf("cus_seed_00003", "cus_seed_00004", "cus_seed_00005")

                val one =
                    Json
                        .parseToJsonElement(
                            client.get("/v1/customers/cus_seed_00001") { bearerAuth("api-token") }.bodyAsText(),
                        ).jsonObject
                one["version"]!!.jsonPrimitive.content shouldBe "1"
                one["loyalty_tier"] shouldNotBe null // 契約の写しにない項目(受け手の Tolerant Reader の確認用)
                one.containsKey("address") shouldBe false
                client.get("/v1/customers/cus_none") { bearerAuth("api-token") }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("updated_since の後に変わった顧客だけ。不正な limit・cursor・updated_since は 400") {
            val clock = MutableClock(start)
            val mock = mock(clock)
            SaasMockServer.seed(mock.customers, 2)
            clock.now = start + 1.minutes
            mock.customers.update("cus_seed_00002", CustomerInput(displayName = "Changed"))
            withMock(mock) {
                val body = client.get("/v1/customers?updated_since=${start + 30.seconds}") { bearerAuth("api-token") }.bodyAsText()
                val data = Json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray
                data shouldHaveSize 1
                data
                    .single()
                    .jsonObject["version"]!!
                    .jsonPrimitive.content shouldBe "2"
                listOf("limit=0", "limit=101", "limit=x", "cursor=%25%25", "updated_since=yesterday").forEach { query ->
                    client.get("/v1/customers?$query") { bearerAuth("api-token") }.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        test("API トークンがない・違えば 401。管理のトークンでは CRM の API を使えない。トークンを読めなければ 503") {
            withMock(mock()) {
                client.get("/v1/customers").status shouldBe HttpStatusCode.Unauthorized
                client.get("/v1/customers") { bearerAuth("wrong") }.status shouldBe HttpStatusCode.Unauthorized
                client.get("/v1/customers") { bearerAuth("admin-token") }.status shouldBe HttpStatusCode.Unauthorized
                client.get("/admin/webhooks") { bearerAuth("api-token") }.status shouldBe HttpStatusCode.Unauthorized
                client.get("/health/ready").status shouldBe HttpStatusCode.OK
            }
            val noSecrets = mock().let { SaasMock(it.customers, it.limiter, it.dispatcher, EnvSecretProvider(emptyMap())) }
            withMock(noSecrets) {
                client.get("/v1/customers") { bearerAuth("api-token") }.status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }

        test("Rate Limit を超えたら 429 と Retry-After(秒)。管理の API で変えられる") {
            val mock = mock(limit = RateLimiter.Limit(2, 10.seconds))
            withMock(mock) {
                repeat(2) { client.get("/v1/customers") { bearerAuth("api-token") }.status shouldBe HttpStatusCode.OK }
                val limited = client.get("/v1/customers") { bearerAuth("api-token") }
                limited.status shouldBe HttpStatusCode.TooManyRequests
                limited.headers[HttpHeaders.RetryAfter]!!.toLong() shouldBe 5
                Json
                    .parseToJsonElement(limited.bodyAsText())
                    .jsonObject["error"]!!
                    .jsonPrimitive.content shouldBe "rate_limited"

                client
                    .put("/admin/rate-limit") {
                        bearerAuth("admin-token")
                        setBody("""{"limit":"bad"}""")
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .put("/admin/rate-limit") {
                        bearerAuth("admin-token")
                        setBody("""{"limit":"1/1m"}""")
                    }.status shouldBe HttpStatusCode.NoContent
                mock.limiter.current() shouldBe RateLimiter.Limit(1, 1.minutes)
            }
        }

        test("管理の API で作る・変えると版が増え、Webhook のイベントができる。遅らせる・送り直す") {
            val mock = mock()
            withMock(mock) {
                val created =
                    client.post("/admin/customers") {
                        bearerAuth("admin-token")
                        setBody(
                            """{"id":"cus_a","display_name":"Alice","email":"a@example.com",""" +
                                """"address":{"country":"JP","postal_code":"100-0001","city":"Chiyoda","line1":"1-1"}}""",
                        )
                    }
                created.status shouldBe HttpStatusCode.Created
                val address = Json.parseToJsonElement(created.bodyAsText()).jsonObject["address"]!!.jsonObject
                address.containsKey("region") shouldBe false
                client
                    .post("/admin/customers") {
                        bearerAuth("admin-token")
                        setBody("""{"id":"cus_a","display_name":"Alice","email":"a@example.com"}""")
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post("/admin/customers") {
                        bearerAuth("admin-token")
                        setBody("""{"display_name":"No email"}""")
                    }.status shouldBe HttpStatusCode.BadRequest

                val updated =
                    client.patch("/admin/customers/cus_a?webhookDelay=30s") {
                        bearerAuth("admin-token")
                        setBody("""{"email":"alice@example.com"}""")
                    }
                Json
                    .parseToJsonElement(updated.bodyAsText())
                    .jsonObject["version"]!!
                    .jsonPrimitive.content shouldBe "2"
                client
                    .patch("/admin/customers/cus_none") {
                        bearerAuth("admin-token")
                        setBody("{}")
                    }.status shouldBe HttpStatusCode.NotFound
                client
                    .patch("/admin/customers/cus_a?webhookDelay=-1s") {
                        bearerAuth("admin-token")
                        setBody("{}")
                    }.status shouldBe HttpStatusCode.BadRequest

                val statuses = mock.dispatcher!!.statuses()
                statuses.map { it.type }.sorted() shouldBe listOf("customer.created", "customer.updated")
                val listed = client.get("/admin/webhooks") { bearerAuth("admin-token") }.bodyAsText()
                Json.parseToJsonElement(listed).jsonArray shouldHaveSize 2
                client.post("/admin/webhooks/${statuses.first().id}/redeliver") { bearerAuth("admin-token") }.status shouldBe
                    HttpStatusCode.Accepted
                client.post("/admin/webhooks/evt_none/redeliver") { bearerAuth("admin-token") }.status shouldBe HttpStatusCode.NotFound
            }
        }
    })
