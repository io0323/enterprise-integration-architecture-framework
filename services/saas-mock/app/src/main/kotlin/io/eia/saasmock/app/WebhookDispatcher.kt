package io.eia.saasmock.app

import io.eia.platform.security.secret.Secret
import io.eia.platform.webhook.StandardWebhooks
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * CRM の Webhook の送り手(Standard Webhooks。ADR-0035 §2)。顧客の変更ごとに 1 つのイベントを作り、受け口に POST する。
 *
 * - 2xx 以外・接続の失敗は、[retrySchedule] の間隔で再送する。**再送のたびに、新しい時刻で署名し直す**(イベント ID と本文は同じ)。
 *   受け口は署名の時刻の幅(前後 5 分)でリプレイを拒否するので、元の時刻のままの再送は拒否される。
 * - 鍵の差し替えの間は、新旧の両方で署名する([secrets] が 2 つを返す)。鍵は送るたびに読む(再起動なしで差し替える)。
 * - 再送を尽くしたイベントは FAILED にして残す(管理の API で送り直せる)。
 *
 * @param secrets 署名の鍵(今の鍵と、差し替えの間は次の鍵)。読めなければ空にする(送らずに次の回に回す)
 */
class WebhookDispatcher(
    private val client: HttpClient,
    private val url: String,
    private val secrets: () -> List<Secret>,
    private val retrySchedule: List<Duration>,
    private val clock: Clock = Clock.System,
) {
    private val deliveries = ConcurrentHashMap<String, Delivery>()

    @Serializable
    data class Status(
        val id: String,
        val type: String,
        val customerId: String,
        val customerVersion: Long,
        val state: State,
        val attempts: Int,
        val lastStatus: Int?,
    )

    enum class State { PENDING, DELIVERED, FAILED }

    private data class Delivery(
        val id: String,
        val type: String,
        val customerId: String,
        val customerVersion: Long,
        val body: ByteArray,
        val state: State,
        val attempts: Int,
        val nextAt: Instant,
        val lastStatus: Int?,
    )

    /** 顧客の変更のイベントを作る([delay] の後に初めて送る。順序の逆転を作るため)。イベント ID を返す。 */
    fun enqueue(
        type: String,
        customer: CrmCustomer,
        delay: Duration = Duration.ZERO,
    ): String {
        val id = "evt_${UUID.randomUUID().toString().replace("-", "")}"
        val body =
            CrmJson
                .encodeToString(
                    buildJsonObject {
                        put("type", type)
                        put("timestamp", clock.now().toString())
                        put("data", CrmJson.encodeToJsonElement(CrmCustomer.serializer(), customer))
                    },
                ).toByteArray()
        deliveries[id] = Delivery(id, type, customer.id, customer.version, body, State.PENDING, 0, clock.now() + delay, null)
        return id
    }

    /** 同じイベントをすぐに送り直す(同じ ID・同じ本文。新しい時刻で署名する)。なければ false。 */
    fun redeliver(id: String): Boolean =
        deliveries.computeIfPresent(id) { _, delivery -> delivery.copy(state = State.PENDING, nextAt = clock.now()) } != null

    fun statuses(): List<Status> =
        deliveries.values
            .sortedBy {
                it.id
            }.map { Status(it.id, it.type, it.customerId, it.customerVersion, it.state, it.attempts, it.lastStatus) }

    /** 送る時刻の来たイベントを 1 回ずつ送る(常駐のループが呼ぶ)。送った数を返す。 */
    suspend fun dispatchDue(): Int {
        val now = clock.now()
        val due = deliveries.values.filter { it.state == State.PENDING && it.nextAt <= now }.sortedBy { it.nextAt }
        due.forEach { deliver(it) }
        return due.size
    }

    private suspend fun deliver(delivery: Delivery) {
        val keys = secrets()
        if (keys.isEmpty()) {
            logger.warn("Webhook の署名の鍵を読めないので、{} を送りません(次の回に送ります)", delivery.id)
            return
        }
        val signedAt = clock.now()
        val status =
            try {
                client
                    .post(url) {
                        contentType(ContentType.Application.Json)
                        header(StandardWebhooks.ID, delivery.id)
                        header(StandardWebhooks.TIMESTAMP, signedAt.epochSeconds.toString())
                        header(StandardWebhooks.SIGNATURE, StandardWebhooks.signatureHeader(keys, delivery.id, signedAt, delivery.body))
                        setBody(delivery.body)
                    }.status
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.warn("Webhook {} を送れません({})", delivery.id, e::class.simpleName)
                null
            }
        val attempts = delivery.attempts + 1
        deliveries[delivery.id] =
            when {
                status?.isSuccess() == true -> {
                    delivery.copy(state = State.DELIVERED, attempts = attempts, lastStatus = status.value)
                }

                attempts > retrySchedule.size -> {
                    delivery.copy(state = State.FAILED, attempts = attempts, lastStatus = status?.value).also {
                        logger.warn("Webhook {} の再送を尽くしました(FAILED)", delivery.id)
                    }
                }

                else -> {
                    delivery.copy(attempts = attempts, lastStatus = status?.value, nextAt = clock.now() + retrySchedule[attempts - 1])
                }
            }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(WebhookDispatcher::class.java)
    }
}
