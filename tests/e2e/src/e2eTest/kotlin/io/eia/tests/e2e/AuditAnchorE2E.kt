@file:Suppress("MagicNumber") // HTTP の状態コード・待ち時間・件数

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.E2eEnvironment.PROMETHEUS
import io.eia.tests.e2e.E2eEnvironment.encode
import io.eia.tests.e2e.E2eEnvironment.get
import io.eia.tests.e2e.E2eEnvironment.json
import io.eia.tests.e2e.E2eEnvironment.request
import io.eia.tests.e2e.E2eEnvironment.sendRespectingRateLimit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Prometheus の即時の問い合わせの、最初の系列の値(系列がなければ null)。 */
private fun value(expr: String): Double? =
    json(get("$PROMETHEUS/api/v1/query?query=${encode(expr)}"))
        .jsonObject
        .getValue("data")
        .jsonObject
        .getValue("result")
        .jsonArray
        .firstOrNull()
        ?.jsonObject
        ?.getValue("value")
        ?.jsonArray
        ?.get(1)
        ?.jsonPrimitive
        ?.content
        ?.toDouble()

private const val SERVICE = """job="order-service""""

/**
 * 監査のアンカーの定期的な保存(ADR-0017 §5)。注文の受け付けの記録を、order-service が次の検査(compose では 1 分ごと)で
 * 差分を検証してアンカーに保存することを、メトリクスで確かめる。全体の検査(make audit-verify)は、`make e2e` がシナリオの後に行う。
 */
class AuditAnchorE2E :
    FunSpec({
        test("注文の受け付けの記録を、次の検査でアンカーに保存し、保存を拒否した回がなく、アラートの候補の条件に当たらない") {
            val placedAt = Instant.now().epochSecond.toDouble()
            sendRespectingRateLimit(
                request(ORDERS)
                    .header("Idempotency-Key", "e2e-anchor-${UUID.randomUUID()}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(ORDER_BODY)),
            ).statusCode() shouldBe 201

            // 検査の間隔(1 分)+ メトリクスの送信(10 秒)+ scrape(15 秒)
            val published =
                eventually(timeout = Duration.ofSeconds(180), interval = Duration.ofSeconds(5)) {
                    value("max(eia_audit_anchor_last_published_seconds{$SERVICE})")?.takeIf { it >= placedAt }
                }.shouldNotBeNull()
            published shouldBeGreaterThanOrEqual placedAt

            (value("sum(eia_audit_anchor_checks_total{$SERVICE,outcome=\"rejected\"})") ?: 0.0) shouldBe 0.0
            // 最後に検査が成功してからの経過 - 間隔の 2 倍(負なら条件に当たらない)。ラベルが一致して系列があること
            value(
                "time() - eia_audit_anchor_last_success_seconds{$SERVICE} - 2 * eia_audit_anchor_interval_seconds{$SERVICE}",
            ).shouldNotBeNull() shouldBeLessThan 0.0
        }
    })
