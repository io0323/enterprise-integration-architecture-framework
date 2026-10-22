@file:Suppress("MagicNumber") // 待ち時間・版・HTTP のステータス

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.GATEWAY
import io.eia.tests.e2e.E2eEnvironment.json
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val CRM = "http://localhost:19280"
private const val WEBHOOK = "$GATEWAY/sales/v1/webhooks/crm"

/** 送り手(saas-mock)の署名の鍵(infra/local/secrets/crm-webhook。make env が作る)。リプレイの確認で、正しい鍵の署名を作るために読む。 */
private fun senderKey(): ByteArray {
    val file = Path.of(System.getProperty("eiaf.repo.root", ".")).resolve("infra/local/secrets/crm-webhook/sender-current")
    val value = Files.readString(file).removeSuffix("\n")
    return if (value.startsWith("whsec_")) Base64.getDecoder().decode(value.removePrefix("whsec_")) else value.toByteArray()
}

/** Standard Webhooks の署名(`{id}.{timestamp}.{body}` の HMAC-SHA256)。 */
private fun sign(
    key: ByteArray,
    id: String,
    timestamp: Long,
    body: String,
): String {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    return "v1," + Base64.getEncoder().encodeToString(mac.doFinal("$id.$timestamp.$body".toByteArray()))
}

/** Gateway の署名のルートに、送り手と同じ形で POST する。 */
private fun postWebhook(
    id: String,
    timestamp: Long,
    signature: String,
    body: String,
): HttpResponse<String> =
    // Gateway の Webhook のルートの Rate Limit(make verify が枠を使い切っていても、ほかの確認を失敗させない)
    E2eEnvironment.sendRespectingRateLimit(
        HttpRequest
            .newBuilder(URI.create(WEBHOOK))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("webhook-id", id)
            .header("webhook-timestamp", timestamp.toString())
            .header("webhook-signature", signature)
            .POST(HttpRequest.BodyPublishers.ofString(body)),
    )

/** saas-mock の管理の API(SaaS の画面の代わり)。 */
private fun admin(
    method: String,
    path: String,
    body: String? = null,
): HttpResponse<String> =
    E2eEnvironment.send(
        HttpRequest
            .newBuilder(URI.create("$CRM$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${E2eEnvironment.secret("SAAS_MOCK_ADMIN_TOKEN")}")
            .header("Content-Type", "application/json")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()),
    )

private fun customerJson(
    id: String,
    version: Int,
): String =
    """{"id":"$id","display_name":"E2E $id","email":"$id@example.com","created_at":"2026-10-22T00:00:00Z",""" +
        """"updated_at":"2026-10-22T0$version:00:00Z","version":$version}"""

/** saas-mock が送った Webhook の状態(`id`・`customerId`・`customerVersion`・`state`・`attempts`)。 */
private fun webhooks(): List<Map<String, String>> =
    json(admin("GET", "/admin/webhooks").body()).jsonArray.map { item ->
        item.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
    }

private fun unique(prefix: String): String = "cus_${prefix}_${HexFormat.of().formatHex(ByteArray(4).also(SecureRandom()::nextBytes))}"

/**
 * SaaS 連携(P10。ADR-0035・ADR-0036。ROADMAP P10 の DoD)。`make up PROFILE="... saas" E2E=1` で起動した基盤に、Gateway・saas-mock の
 * 管理の API(SaaS の画面の代わり)・Kafka・Prometheus・Tempo だけで接続する。
 */
class SaasE2E :
    FunSpec({
        test("DoD: 署名が不正な Webhook は Gateway 経由で 401 で拒否し、取り込まない") {
            val id = unique("forged")
            val body = """{"type":"customer.created","data":${customerJson(id, 1)}}"""
            val now = Instant.now().epochSecond
            val forged = postWebhook("evt_$id", now, sign("not-the-key".toByteArray(), "evt_$id", now, body), body)
            forged.statusCode() shouldBe 401
            forged.body() shouldContain "webhook-signature-invalid"
            customerEvents(id, timeout = Duration.ofSeconds(15)) shouldBe emptyList()
        }

        test("リプレイ: 正しい鍵の署名でも、署名の時刻が 5 分より古ければ 401。今の時刻で署名し直せば取り込む") {
            val id = unique("replay")
            val body = """{"type":"customer.created","data":${customerJson(id, 1)}}"""
            val key = senderKey()
            val old = Instant.now().minusSeconds(6 * 60).epochSecond
            val replay = postWebhook("evt_$id", old, sign(key, "evt_$id", old, body), body)
            replay.statusCode() shouldBe 401
            replay.body() shouldContain "webhook-timestamp-out-of-range"

            val now = Instant.now().epochSecond
            postWebhook("evt_$id", now, sign(key, "evt_$id", now, body), body).statusCode() shouldBe 204
            customerEvents(id).single().first.sourceVersion() shouldBe 1
        }

        test("同じ Webhook の再送(同じ webhook-id)は、受け付けても二度は取り込まない。発行のトレースは受け口から Flow までつながる") {
            val id = unique("redeliver")
            val created = admin("POST", "/admin/customers", """{"id":"$id","display_name":"Redeliver","email":"Redeliver@Example.COM"}""")
            created.statusCode() shouldBe 201
            val webhookId = created.header("X-Webhook-Id") ?: fail("X-Webhook-Id がありません")
            val (event, traceparent) = customerEvents(id).single()
            event.customerName() shouldBe "Redeliver"
            val duplicatesBefore = metricSum("""sum(eia_webhook_requests_total{outcome="duplicate"})""")

            admin("POST", "/admin/webhooks/$webhookId/redeliver").statusCode() shouldBe 202
            eventually { webhooks().firstOrNull { it["id"] == webhookId && it["attempts"] != "1" && it["state"] == "DELIVERED" } }
                ?: fail("送り直しが届きません")
            eventually { metricSum("""sum(eia_webhook_requests_total{outcome="duplicate"})""").takeIf { it > duplicatesBefore } }
                ?: fail("受け口が再送を重複として数えません")
            customerEvents(id, enough = 2, timeout = Duration.ofSeconds(15)).size shouldBe 1

            // traceparent: 受け口の受け取り → Outbox → Debezium → Flow の購読 → Outbox → Debezium(Framework 14.1)
            val traceId = traceparent?.split('-')?.getOrNull(1) ?: fail("traceparent がありません")
            eventually(Duration.ofSeconds(90), Duration.ofSeconds(5)) {
                traceServiceNames(traceId).takeIf { it.containsAll(listOf("webhook-receiver", "integration-flow")) }
            } ?: fail("trace $traceId に webhook-receiver と integration-flow の span がそろいません: ${traceServiceNames(traceId)}")
        }

        test("順序の逆転: 遅れて届いた古い版(2)は出さず、新しい版(3)だけを出す(Webhook の順序は保証しない)") {
            val id = unique("order")
            admin("POST", "/admin/customers", """{"id":"$id","display_name":"Order","email":"order@example.com"}""").statusCode() shouldBe
                201
            customerEvents(id).single().first.sourceVersion() shouldBe 1
            // 版 2 の Webhook を 10 秒遅らせ、版 3 を先に届ける
            admin("PATCH", "/admin/customers/$id?webhookDelay=10s", """{"display_name":"Order v2"}""").statusCode() shouldBe 200
            admin("PATCH", "/admin/customers/$id", """{"display_name":"Order v3"}""").statusCode() shouldBe 200
            customerEvents(id, enough = 2).map { it.first.sourceVersion() } shouldBe listOf(1, 3)
            eventually { webhooks().firstOrNull { it["customerId"] == id && it["customerVersion"] == "2" && it["state"] == "DELIVERED" } }
                ?: fail("遅らせた版 2 の Webhook が届きません")
            // 版 2 が届いた後も、版 1 と 3 だけ
            val events = customerEvents(id, enough = 3, timeout = Duration.ofSeconds(20))
            events.map { it.first.sourceVersion() } shouldBe listOf(1, 3)
            events.last().first.customerName() shouldBe "Order v3"
        }

        test("DoD: SaaS の 429 で Backoff する(Retry-After を待って再試行し、Rate Limit を戻すと同期が成功する)") {
            val retries = """sum(eia_resilience_retries_total{eia_dependency_name="saas-crm"})"""
            val succeeded = """sum(eia_flow_sync_runs_total{outcome="succeeded"})"""
            val retriesBefore = metricSum(retries)
            try {
                admin("PUT", "/admin/rate-limit", """{"limit":"1/8s"}""").statusCode() shouldBe 204
                eventually(Duration.ofSeconds(120), Duration.ofSeconds(5)) { metricSum(retries).takeIf { it > retriesBefore } }
                    ?: fail("CRM の API の 429 で eia.resilience.retries(saas-crm)が増えません")
            } finally {
                admin("PUT", "/admin/rate-limit", """{"limit":"20/1s"}""")
            }
            val succeededAfter = metricSum(succeeded)
            eventually(Duration.ofSeconds(120), Duration.ofSeconds(5)) { metricSum(succeeded).takeIf { it > succeededAfter } }
                ?: fail("Rate Limit を戻した後に、定期の同期が成功しません")
        }

        test("DoD: Flow の定義を足すだけで新しいマッピングを追加できる(E2E の Flow crm-customer-reactivated が customer.reactivated を写す)") {
            val id = unique("reactivated")
            admin(
                "POST",
                "/admin/customers",
                """{"id":"$id","display_name":"Reactivated","email":"reactivated@example.com"}""",
            ).statusCode() shouldBe
                201
            customerEvents(id).single().first.customerName() shouldBe "Reactivated"
            // crm-customer は customer.reactivated を取り込まない。足した Flow(tests/e2e/flows)だけが写す(名前を大文字にする)
            admin("POST", "/admin/customers/$id/events", """{"type":"customer.reactivated"}""").statusCode() shouldBe 202
            customerEvents(id, enough = 2).map { it.first.customerName() } shouldContainAll listOf("Reactivated", "REACTIVATED")
        }
    })
