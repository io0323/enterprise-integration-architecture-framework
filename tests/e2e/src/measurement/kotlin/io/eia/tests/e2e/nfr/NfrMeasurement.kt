@file:Suppress("MagicNumber") // 既定の件数・秒数

package io.eia.tests.e2e.nfr

import io.eia.tests.e2e.E2eEnvironment
import io.eia.tests.e2e.E2eEnvironment.Client
import io.eia.tests.e2e.eventually
import java.time.Duration
import kotlin.system.exitProcess

private fun property(
    name: String,
    default: String,
): String = System.getProperty("nfr.$name")?.takeIf { it.isNotBlank() } ?: default

/** 起動直後の Keycloak は最初のトークンの応答が遅いので、負荷の前に取れるまで待つ(取れたトークンは使い回される)。 */
private fun warmTokens() {
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
    warmTokens()
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
