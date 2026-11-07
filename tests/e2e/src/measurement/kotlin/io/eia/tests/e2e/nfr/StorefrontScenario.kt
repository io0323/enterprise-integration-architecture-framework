@file:Suppress("MagicNumber") // 状態コード・件数・待ち時間・複雑度

package io.eia.tests.e2e.nfr

import io.eia.tests.e2e.E2eEnvironment
import io.eia.tests.e2e.E2eEnvironment.Client
import io.eia.tests.e2e.E2eEnvironment.GATEWAY
import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.ORDER_BODY
import io.eia.tests.e2e.eventually
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.http.HttpRequest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val STOREFRONT = "$GATEWAY/storefront/v1/graphql"
private val CALLER_SCOPES = setOf("bff-graphql-audience", "sales.order:read", "inventory.stock:read", "shipping.shipment:read")
private const val FULL_ORDER = "id status lines { sku stock { available } } shipment { status shipmentId }"

/** 計らない慣らしの送信の長さ(秒)。 */
internal const val WARM_UP_SECONDS = 15

/** 画面の代表のクエリと複雑度(ADR-0043 §4 の数え方。1 明細の注文でも 5 明細と仮定して数える)。 */
internal enum class StorefrontQuery(
    val complexity: Int,
) {
    /** 注文 1 件の詳細(明細の在庫と出荷)。1 + 5 × 1 + 1。 */
    SINGLE(7),

    /** 注文 20 件の一覧(明細の在庫と出荷)。20 × (1 + 5 + 1)。 */
    LIST20(140),
    ;

    fun text(ids: List<String>): String =
        when (this) {
            SINGLE -> "{ order(id: \"${ids.random()}\") { $FULL_ORDER } }"
            LIST20 -> "{ orders(ids: [${ids.shuffled().take(20).joinToString(",") { "\"$it\"" }}]) { orders { $FULL_ORDER } notFound } }"
        }
}

/** 出荷まで進んだ注文を [count] 件作る(BFF が在庫と出荷も読めるように)。 */
internal fun shippedOrders(count: Int): List<String> {
    val ids =
        (1..count).map {
            val response =
                E2eEnvironment.send(
                    E2eEnvironment
                        .request(ORDERS)
                        .header("Idempotency-Key", "nfr-${UUID.randomUUID()}")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(ORDER_BODY)),
                )
            check(response.statusCode() == 201) { "注文を作れません(${response.statusCode()})" }
            Regex(""""id":"([^"]+)"""").find(response.body())!!.groupValues[1]
        }
    ids.forEach { id ->
        eventually(Duration.ofMinutes(5), Duration.ofSeconds(2)) {
            E2eEnvironment
                .send(E2eEnvironment.request("$ORDERS/$id").GET())
                .body()
                .takeIf { it.contains("\"status\":\"SHIPPED\"") }
        } ?: error("注文 $id が SHIPPED になりません")
    }
    return ids
}

/** GraphQL の結果。 */
internal data class StorefrontResults(
    val latencies: Latencies = Latencies(),
    val outcomes: Counts = Counts(),
)

/**
 * [clients] のそれぞれが [rate] 件 / 秒で [query] を [seconds] 秒送る(予定の時刻に送る open loop。応答を待たずに次を送るので、
 * 遅くなっても送る量が減らない)。HTTP の状態と、部分応答(`errors` つきの 200)・枠の不足(429)を数える。
 */
internal fun loadStorefront(
    query: StorefrontQuery,
    ids: List<String>,
    clients: List<Client>,
    rate: Double,
    seconds: Int,
): StorefrontResults {
    val results = StorefrontResults()
    val tokens = clients.associateWith { E2eEnvironment.token(it, CALLER_SCOPES) }
    val interval = (1_000_000_000 / rate).toLong()
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val start = System.nanoTime()
    val total = (rate * seconds).toInt()
    for (i in 0 until total) {
        val planned = start + i * interval
        val wait = planned - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        clients.forEach { client ->
            // トークンは期限の前に取り直す(E2eEnvironment がクライアントごとに使い回す)
            val token = if (i % 100 == 0) E2eEnvironment.token(client, CALLER_SCOPES) else tokens.getValue(client)
            executor.execute { sendQuery(query, ids, token, results) }
        }
    }
    executor.shutdown()
    check(executor.awaitTermination(2, TimeUnit.MINUTES)) { "GraphQL の応答が返りません" }
    return results
}

private fun sendQuery(
    query: StorefrontQuery,
    ids: List<String>,
    token: String,
    results: StorefrontResults,
) {
    val request =
        E2eEnvironment
            .request(STOREFRONT, client = null)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("X-Correlation-Id", "nfr-bff-${UUID.randomUUID()}")
            .POST(HttpRequest.BodyPublishers.ofString(buildJsonObject { put("query", query.text(ids)) }.toString()))
    val started = System.nanoTime()
    val response = orNullOnIoError { E2eEnvironment.send(request) }
    results.latencies.addNanos(System.nanoTime() - started)
    val outcome =
        when {
            response == null -> {
                "io-error"
            }

            response.statusCode() != 200 -> {
                "HTTP ${response.statusCode()}"
            }

            else -> {
                val errors = E2eEnvironment.json(response.body()).jsonObject["errors"] as? JsonArray
                if (errors.isNullOrEmpty()) {
                    "200 complete"
                } else {
                    "200 partial " +
                        errors.joinToString(",") {
                            it.jsonObject["extensions"]
                                ?.jsonObject
                                ?.get("code")
                                ?.jsonPrimitive
                                ?.content ?: "?"
                        }
                }
            }
        }
    results.outcomes.add(outcome)
}

/** INT-STOREFRONT-001 と、BFF が呼ぶ gRPC(INT-INVENTORY-003・INT-SHIPPING-003)。 */
internal fun storefront(
    report: Report,
    rate: Double,
    seconds: Int,
) {
    val ids = shippedOrders(40)
    // 画面のスコープを持つ E2E のクライアントは eiaf-e2e だけ。複雑度の枠はクライアントごとなので、1 クライアントの枠の中で送る
    val clients = listOf(Client.A)
    report.value("storefront orders", "${ids.size} 件(SHIPPED)")
    // 計らない: JIT と接続を温め、Gateway の系列を作ってから窓を始める
    loadStorefront(StorefrontQuery.SINGLE, ids, clients, rate, WARM_UP_SECONDS)
    Prometheus.startWindow(report)
    // 1. 注文 1 件の詳細: クライアントごとに複雑度 7 × rate / 秒(枠の戻りは 60 / 秒)
    val single = loadStorefront(StorefrontQuery.SINGLE, ids, clients, rate, seconds)
    report.value(
        "SINGLE load",
        "${clients.size} クライアント × ${"%.1f".format(rate)} 件/秒 × ${seconds}s(複雑度 ${StorefrontQuery.SINGLE.complexity})",
    )
    report.value("SINGLE outcomes", single.outcomes.json())
    report.latency("INT-STOREFRONT-001 SINGLE (client)", single.latencies.summary())
    report.value("INT-STOREFRONT-001 SINGLE > 1s ratio", "%.4f".format(single.latencies.ratioAbove(1000.0)))
    // 2. 注文 20 件の一覧: 複雑度 140。枠の戻り(60 / 秒)の中で続けられる 0.4 件 / 秒
    val list = loadStorefront(StorefrontQuery.LIST20, ids, clients, 0.4, seconds)
    report.value("LIST20 load", "${clients.size} クライアント × 0.4 件/秒 × ${seconds}s(複雑度 ${StorefrontQuery.LIST20.complexity})")
    report.value("LIST20 outcomes", list.outcomes.json())
    report.latency("INT-STOREFRONT-001 LIST20 (client)", list.latencies.summary())
    // 3. 枠を超える送り方: 1 クライアントが複雑度 7 を 15 件 / 秒(105 / 秒。戻りの 60 / 秒を超える)
    val burst = loadStorefront(StorefrontQuery.SINGLE, ids, listOf(Client.A), 15.0, 60)
    report.value("OVER-BUCKET load", "1 クライアント × 15 件/秒 × 60s(複雑度 105 / 秒)")
    report.value("OVER-BUCKET outcomes", burst.outcomes.json())

    Prometheus.awaitScrape()
    val window = Prometheus.window(report.windowStart)
    gatewayView(report, "storefront-graphql", window)
    val grpc = listOf("rpc_client_call_duration_seconds" to "bff-graphql", "rpc_server_call_duration_seconds" to "provider")
    grpc.forEach { (metric, side) ->
        Prometheus
            .query("histogram_quantile(0.99, sum by (le, job, rpc_service) (increase(${metric}_bucket[$window])))")
            .filter { (_, value) -> !value.isNaN() }
            .forEach { (labels, value) ->
                report.value("gRPC $side p99 ${labels["job"]} ${labels["rpc_service"]} (ms)", "%.1f".format(value * 1000))
            }
    }
    callerDeadline(report, window)
}
