package io.eia.saasmock.app

import io.eia.platform.security.secret.SecretProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** saas-mock の状態と依存(ルートに渡す)。[dispatcher] がなければ Webhook を送らない。 */
internal class SaasMock(
    val customers: CrmCustomers,
    val limiter: RateLimiter,
    val dispatcher: WebhookDispatcher?,
    val secrets: SecretProvider,
)

/**
 * ルート。
 *
 * - CRM の API(外部の契約 crm-api.v1.yaml): `GET /v1/customers`・`GET /v1/customers/{id}`。API トークンで認証し、Rate Limit を当てる。
 * - 管理の API(SaaS の画面の代わり。E2E が使う。管理のトークンで認証する。Rate Limit は当てない):
 *   - `POST /admin/customers`・`PATCH /admin/customers/{id}`: 顧客を作る・変える。`customer.created`・`customer.updated` の Webhook を送る。
 *     `?webhookDelay=10s` で初めて送るまで待たせる(順序の逆転を作る)。
 *   - `POST /admin/customers/{id}/events`: 顧客の今の状態で任意の種類の Webhook を送る(`{"type":"customer.reactivated"}`)。
 *     作った Webhook のイベント ID は `X-Webhook-Id`(作成・変更も同じ)。
 *   - `GET /admin/webhooks`: 送ったイベントの状態。`POST /admin/webhooks/{id}/redeliver`: 同じイベントを送り直す。
 *   - `PUT /admin/rate-limit`: Rate Limit を変える(`{"limit":"1/10s"}`)。
 */
internal fun Application.saasMock(mock: SaasMock) {
    routing {
        get("/health/live") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
        get("/health/ready") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
        crmApi(mock)
        admin(mock)
    }
}

private fun Route.crmApi(mock: SaasMock) {
    get("/v1/customers") {
        if (!call.authorizeApi(mock)) return@get
        val params = call.request.queryParameters
        val limit = params["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..MAX_LIMIT } ?: return@get call.badRequest("limit") }
        val updatedSince =
            params["updated_since"]?.let { Instant.parseOrNull(it) ?: return@get call.badRequest("updated_since") }
        val cursor = params["cursor"]
        if (cursor != null && CrmCustomers.decodeCursor(cursor) == null) return@get call.badRequest("cursor")
        val page = mock.customers.page(cursor, limit ?: DEFAULT_LIMIT, updatedSince)
        val body =
            buildJsonObject {
                put("data", CrmJson.encodeToJsonElement(ListSerializer(CrmCustomer.serializer()), page.data))
                put("next_cursor", page.nextCursor)
            }
        call.respondJson(CrmJson.encodeToString(body))
    }
    get("/v1/customers/{id}") {
        if (!call.authorizeApi(mock)) return@get
        val customer =
            mock.customers.get(call.parameters["id"].orEmpty()) ?: return@get call.error(HttpStatusCode.NotFound, "not_found")
        call.respondJson(CrmJson.encodeToString(CrmCustomer.serializer(), customer))
    }
}

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 100

/** API トークンで認証し、Rate Limit を当てる。通らなければ応答して false。 */
private suspend fun ApplicationCall.authorizeApi(mock: SaasMock): Boolean {
    if (!authorize(mock, SaasMockConfig.API_TOKEN)) return false
    val retryAfter = mock.limiter.acquire()
    if (retryAfter != null) {
        response.header(HttpHeaders.RetryAfter, retryAfter.toString())
        error(HttpStatusCode.TooManyRequests, "rate_limited")
    }
    return retryAfter == null
}
