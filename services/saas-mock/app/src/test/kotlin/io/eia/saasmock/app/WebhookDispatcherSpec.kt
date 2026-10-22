package io.eia.saasmock.app

import io.eia.platform.security.secret.Secret
import io.eia.platform.webhook.StandardWebhooks
import io.eia.platform.webhook.WebhookHeaders
import io.eia.platform.webhook.WebhookVerifier
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class MutableClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/** 受け口が受け取った 1 回の送信(ヘッダと生の本文)。 */
private data class Received(
    val headers: WebhookHeaders,
    val contentType: String?,
    val body: ByteArray,
)

class WebhookDispatcherSpec :
    FunSpec({
        val current = Secret("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")
        val next = Secret("next-secret-value")
        val start = Instant.parse("2026-10-21T00:00:00Z")

        fun customer() = CrmCustomer("cus_1", "Alice", "alice@example.com", start.toString(), start.toString(), 1)

        /** [statuses] の順に応答する受け口(尽きたら最後の応答)。 */
        fun receiver(vararg statuses: HttpStatusCode): Pair<HttpClient, MutableList<Received>> {
            val received = mutableListOf<Received>()
            val engine =
                MockEngine { request ->
                    received +=
                        Received(
                            WebhookHeaders(
                                request.headers[StandardWebhooks.ID],
                                request.headers[StandardWebhooks.TIMESTAMP],
                                request.headers[StandardWebhooks.SIGNATURE],
                            ),
                            request.body.contentType?.toString(),
                            request.body.toByteArray(),
                        )
                    respond("", statuses[minOf(received.size, statuses.size) - 1])
                }
            return HttpClient(engine) to received
        }

        test("2xx で DELIVERED。本文は type・timestamp・data(顧客)で、署名は受け口の検証を通る") {
            val clock = MutableClock(start)
            val (client, received) = receiver(HttpStatusCode.NoContent)
            val dispatcher = WebhookDispatcher(client, "http://receiver/v1/webhooks/crm", { listOf(current) }, listOf(5.seconds), clock)
            val id = dispatcher.enqueue("customer.created", customer())
            dispatcher.dispatchDue() shouldBe 1

            received shouldHaveSize 1
            val sent = received.single()
            sent.headers.id shouldBe id
            sent.contentType shouldStartWith "application/json"
            WebhookVerifier().verify(sent.headers, sent.body, listOf(current), clock.now).shouldBeInstanceOf<Result.Ok<*>>()
            val body = Json.parseToJsonElement(sent.body.decodeToString()).jsonObject
            body["type"]?.jsonPrimitive?.content shouldBe "customer.created"
            body["data"]
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.content shouldBe "cus_1"
            body["data"]?.jsonObject?.containsKey("address") shouldBe false
            dispatcher.statuses().single() shouldBe
                WebhookDispatcher.Status(id, "customer.created", "cus_1", 1, WebhookDispatcher.State.DELIVERED, 1, 204)
            dispatcher.dispatchDue() shouldBe 0
        }

        test("再送のたびに新しい時刻で署名し直す(ID と本文は同じ)。3 時間後の再送も受け口の検証を通る") {
            val clock = MutableClock(start)
            val (client, received) = receiver(HttpStatusCode.ServiceUnavailable, HttpStatusCode.ServiceUnavailable, HttpStatusCode.OK)
            val dispatcher = WebhookDispatcher(client, "http://receiver", { listOf(current) }, listOf(1.minutes, 3.hours), clock)
            dispatcher.enqueue("customer.updated", customer())

            dispatcher.dispatchDue() shouldBe 1
            clock.now = start + 30.seconds
            dispatcher.dispatchDue() shouldBe 0 // まだ再送の時刻ではない
            clock.now = start + 1.minutes
            dispatcher.dispatchDue() shouldBe 1
            clock.now = start + 1.minutes + 3.hours
            dispatcher.dispatchDue() shouldBe 1

            received shouldHaveSize 3
            received.map { it.headers.id }.distinct() shouldHaveSize 1
            received.map { it.body.decodeToString() }.distinct() shouldHaveSize 1
            received.map { it.headers.timestamp } shouldBe
                listOf(start, start + 1.minutes, start + 1.minutes + 3.hours).map { it.epochSeconds.toString() }
            val last = received.last()
            WebhookVerifier().verify(last.headers, last.body, listOf(current), clock.now).shouldBeInstanceOf<Result.Ok<*>>()
            // 元の時刻の署名のままなら、受け口はリプレイとして拒否する(だから署名し直す)
            WebhookVerifier().verify(received.first().headers, last.body, listOf(current), clock.now).shouldBeInstanceOf<Result.Err<*>>()
            dispatcher.statuses().single().state shouldBe WebhookDispatcher.State.DELIVERED
            dispatcher.statuses().single().attempts shouldBe 3
        }

        test("再送を尽くしたら FAILED。送り直すと同じ ID で新しい時刻で送る。接続の失敗も再送する") {
            val clock = MutableClock(start)
            val (client, received) = receiver(HttpStatusCode.InternalServerError, HttpStatusCode.InternalServerError, HttpStatusCode.OK)
            val dispatcher = WebhookDispatcher(client, "http://receiver", { listOf(current) }, listOf(1.seconds), clock)
            val id = dispatcher.enqueue("customer.updated", customer())
            dispatcher.dispatchDue()
            clock.now = start + 1.seconds
            dispatcher.dispatchDue()
            dispatcher.statuses().single() shouldBe
                WebhookDispatcher.Status(id, "customer.updated", "cus_1", 1, WebhookDispatcher.State.FAILED, 2, 500)
            clock.now = start + 10.minutes
            dispatcher.dispatchDue() shouldBe 0

            dispatcher.redeliver(id) shouldBe true
            dispatcher.redeliver("evt_unknown") shouldBe false
            dispatcher.dispatchDue() shouldBe 1
            received.last().headers.id shouldBe id
            received.last().headers.timestamp shouldBe (start + 10.minutes).epochSeconds.toString()
            dispatcher.statuses().single().state shouldBe WebhookDispatcher.State.DELIVERED

            val failing = HttpClient(MockEngine { error("connection refused") })
            val down = WebhookDispatcher(failing, "http://receiver", { listOf(current) }, listOf(1.seconds), clock)
            down.enqueue("customer.updated", customer())
            down.dispatchDue()
            down.statuses().single().let {
                it.state shouldBe WebhookDispatcher.State.PENDING
                it.lastStatus shouldBe null
            }
        }

        test("鍵の差し替えの間は新旧の両方で署名する(受け口がどちらの鍵でも通る)。鍵を読めなければ送らない") {
            val clock = MutableClock(start)
            val (client, received) = receiver(HttpStatusCode.OK)
            var keys = emptyList<Secret>()
            val dispatcher = WebhookDispatcher(client, "http://receiver", { keys }, listOf(1.seconds), clock)
            dispatcher.enqueue("customer.created", customer())
            dispatcher.dispatchDue()
            received shouldHaveSize 0
            dispatcher.statuses().single().attempts shouldBe 0

            keys = listOf(current, next)
            dispatcher.dispatchDue()
            val sent = received.single()
            sent.headers.signature!!.split(' ') shouldHaveSize 2
            WebhookVerifier().verify(sent.headers, sent.body, listOf(current), clock.now).shouldBeInstanceOf<Result.Ok<*>>()
            WebhookVerifier().verify(sent.headers, sent.body, listOf(next), clock.now).shouldBeInstanceOf<Result.Ok<*>>()
        }

        test("遅らせたイベントは、その時刻まで送らない(順序の逆転を作る)") {
            val clock = MutableClock(start)
            val (client, received) = receiver(HttpStatusCode.OK)
            val dispatcher = WebhookDispatcher(client, "http://receiver", { listOf(current) }, listOf(1.seconds), clock)
            val first = dispatcher.enqueue("customer.created", customer(), delay = 10.seconds)
            val second = dispatcher.enqueue("customer.updated", customer().copy(version = 2))
            dispatcher.dispatchDue()
            clock.now = start + 10.seconds
            dispatcher.dispatchDue()
            received.map { it.headers.id } shouldBe listOf(second, first)
        }
    })
