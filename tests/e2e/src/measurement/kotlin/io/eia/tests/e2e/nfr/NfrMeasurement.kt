@file:Suppress("MagicNumber") // 既定の件数・秒数

package io.eia.tests.e2e.nfr

import io.eia.tests.e2e.E2eEnvironment
import io.eia.tests.e2e.E2eEnvironment.Client
import io.eia.tests.e2e.eventually
import java.time.Duration
import kotlin.system.exitProcess

/** 注文 API を使うシナリオ(基盤に注文のサービスがある)。 */
private val ORDER_API_SCENARIOS = setOf("order-api", "storefront", "caller-deadline")

private fun property(
    name: String,
    default: String,
): String = System.getProperty("nfr.$name")?.takeIf { it.isNotBlank() } ?: default

/**
 * 起動直後の Keycloak は最初のトークンの応答が遅いので、負荷の前に取れるまで待つ(取れたトークンは使い回される)。
 * 注文 API を使うシナリオ([waitForOrderApi])では、その後、注文 API がトークンを受け付けるまで待つ
 * (注文のサービスのない基盤(B の `cdc iot saas`)では待たない)。
 */
private fun warmTokens(waitForOrderApi: Boolean) {
    // 画面のスコープを持つのは eiaf-e2e だけ(eiaf-e2e-b は注文のスコープだけ)
    val wanted =
        listOf(
            Client.A to setOf("sales.order:read", "sales.order:write"),
            Client.A to setOf("bff-graphql-audience", "sales.order:read", "inventory.stock:read", "shipping.shipment:read"),
        )
    wanted.forEach { (client, scope) ->
        run {
            eventually(Duration.ofMinutes(3), Duration.ofSeconds(5)) {
                try {
                    E2eEnvironment.token(client, scope)
                } catch (e: IllegalStateException) {
                    println("  トークンを待っています: ${e.message}")
                    null
                }
            } ?: error("${client.id} のトークンを取れません")
        }
    }
    if (!waitForOrderApi) return
    // 注文 API が署名の鍵を Keycloak から取れるまでは、正しいトークンでも 401 になる。ない注文を読み、401 でなくなるまで待つ
    eventually(Duration.ofMinutes(3), Duration.ofSeconds(5)) {
        val status = E2eEnvironment.send(E2eEnvironment.request("${E2eEnvironment.ORDERS}/nfr-warm-up").GET()).statusCode()
        if (status == 401 || status >= 500) {
            println("  注文 API がトークンを受け付けるのを待っています(HTTP $status)")
            null
        } else {
            status
        }
    } ?: error("注文 API がトークンを受け付けません")
}

/**
 * NFR の計測(P14 ⑩。docs/reports/p14-nfr.md・ADR-0048)。`make nfr-measure SCENARIO=...` で、起動したローカル基盤に対して手動で実行する。
 * 時間を測るので build と CI には含めない。
 *
 * | シナリオ | 基盤 | 計測する連携 |
 * |---|---|---|
 * | `order-api` | `order saga` | INT-SALES-001(POST・GET)、INT-SALES-002 と Saga の各段(INT-INVENTORY/PAYMENT/SHIPPING-001・002) |
 * | `storefront` | `order saga bff` | INT-STOREFRONT-001、INT-INVENTORY-003・INT-SHIPPING-003(gRPC)、複雑度の枠 |
 * | `webhook` | `saas` | INT-SALES-005、INT-INTEGRATION-001、INT-SALES-007 |
 * | `iot` | `iot` | INT-IOT-001・002 |
 * | `cdc` | `cdc` | INT-SALES-003 |
 * | `caller-deadline` | `order saga bff`(E2E=1) | `caller_deadline` の割合と画面の遅延(ADR-0045 §4) |
 */
fun main() {
    val scenario = property("scenario", "")
    val report = Report(scenario)
    println("NFR の計測: $scenario(${report.startedAt})")
    println("  JVM: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")} ${System.getProperty("os.arch")}")
    warmTokens(waitForOrderApi = scenario in ORDER_API_SCENARIOS)
    when (scenario) {
        "order-api" -> {
            val run = "nfr-${report.startedAt.epochSecond}"
            val correlations = loadOrderApi(report, run, property("requests", "3000").toInt(), property("concurrency", "16").toInt())
            analyzeSaga(report, correlations, report.startedAt.toEpochMilli())
            Prometheus.awaitScrape()
            val window = Prometheus.window(report.windowStart)
            gatewayView(report, "sales-order-api", window)
            callerDeadline(report, window)
        }

        "storefront" -> {
            storefront(report, property("rate", "8").toDouble(), property("seconds", "120").toInt())
        }

        "webhook" -> {
            webhooks(report, property("requests", "200").toInt())
        }

        "iot" -> {
            iot(report, property("requests", "600").toInt(), property("rate", "10").toDouble())
        }

        "cdc" -> {
            cdc(report, property("requests", "4").toInt())
        }

        "caller-deadline" -> {
            callerDeadlineSweep(report, property("rate", "6").toDouble(), property("seconds", "90").toInt())
        }

        else -> {
            System.err.println("SCENARIO は order-api / storefront / webhook / iot / cdc / caller-deadline のどれかです")
            exitProcess(2)
        }
    }
    report.write()
    // Kafka・HTTP のクライアントのスレッドが残っても終わる
    exitProcess(0)
}
