package io.eia.saasmock.app

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration

private const val CUSTOMER_CREATED = "customer.created"
private const val CUSTOMER_UPDATED = "customer.updated"

/** 管理の API(SaaS の画面の代わり)。ルートの一覧は [saasMock]。 */
internal fun Route.admin(mock: SaasMock) {
    adminCustomers(mock)
    adminWebhooks(mock)
}

private fun Route.adminCustomers(mock: SaasMock) {
    post("/admin/customers") {
        if (!call.authorize(mock, SaasMockConfig.ADMIN_TOKEN)) return@post
        val delay = call.webhookDelay() ?: return@post call.badRequest("webhookDelay")
        val input = call.receiveInput() ?: return@post call.badRequest("body")
        if (input.id != null && mock.customers.get(input.id) != null) return@post call.error(HttpStatusCode.Conflict, "conflict")
        val customer = mock.customers.create(input) ?: return@post call.badRequest("body")
        mock.dispatcher?.enqueue(CUSTOMER_CREATED, customer, delay)
        call.respondJson(CrmJson.encodeToString(CrmCustomer.serializer(), customer), HttpStatusCode.Created)
    }
    patch("/admin/customers/{id}") {
        if (!call.authorize(mock, SaasMockConfig.ADMIN_TOKEN)) return@patch
        val delay = call.webhookDelay() ?: return@patch call.badRequest("webhookDelay")
        val input = call.receiveInput() ?: return@patch call.badRequest("body")
        val customer =
            mock.customers.update(call.parameters["id"].orEmpty(), input)
                ?: return@patch call.error(HttpStatusCode.NotFound, "not_found")
        mock.dispatcher?.enqueue(CUSTOMER_UPDATED, customer, delay)
        call.respondJson(CrmJson.encodeToString(CrmCustomer.serializer(), customer))
    }
}

private fun Route.adminWebhooks(mock: SaasMock) {
    get("/admin/webhooks") {
        if (!call.authorize(mock, SaasMockConfig.ADMIN_TOKEN)) return@get
        val statuses = mock.dispatcher?.statuses().orEmpty()
        call.respondJson(CrmJson.encodeToString(ListSerializer(WebhookDispatcher.Status.serializer()), statuses))
    }
    post("/admin/webhooks/{id}/redeliver") {
        if (!call.authorize(mock, SaasMockConfig.ADMIN_TOKEN)) return@post
        if (mock.dispatcher?.redeliver(call.parameters["id"].orEmpty()) == true) {
            call.respond(HttpStatusCode.Accepted)
        } else {
            call.error(HttpStatusCode.NotFound, "not_found")
        }
    }
    put("/admin/rate-limit") {
        if (!call.authorize(mock, SaasMockConfig.ADMIN_TOKEN)) return@put
        val limit =
            call
                .decode(RateLimitInput.serializer())
                ?.let { RateLimiter.parse(it.limit) }
                ?: return@put call.badRequest("limit")
        mock.limiter.change(limit)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** `?webhookDelay=` の期間(なければ 0)。読めなければ null。 */
private fun ApplicationCall.webhookDelay(): Duration? {
    val value = request.queryParameters["webhookDelay"] ?: return Duration.ZERO
    return Duration.parseOrNull(value)?.takeIf { !it.isNegative() }
}

private suspend fun ApplicationCall.receiveInput(): CustomerInput? = decode(CustomerInput.serializer())
