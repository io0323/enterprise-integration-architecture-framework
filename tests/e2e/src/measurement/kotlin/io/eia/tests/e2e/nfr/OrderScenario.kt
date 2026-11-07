@file:Suppress("MagicNumber") // 状態コード・件数・待ち時間

package io.eia.tests.e2e.nfr

import io.eia.tests.e2e.E2eEnvironment
import io.eia.tests.e2e.E2eEnvironment.ORDERS
import io.eia.tests.e2e.ORDER_BODY
import io.eia.tests.e2e.header
import java.net.http.HttpRequest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val ORDER_ID = Regex(""""id":"([^"]+)"""")

/** 注文の作成(POST)と参照(GET)を 1 組で [requests] 回、[concurrency] 本の並列で送る。作った注文の Correlation ID を返す。 */
internal fun loadOrderApi(
    report: Report,
    run: String,
    requests: Int,
    concurrency: Int,
): Set<String> {
    val load = OrderLoad(run)
    val next = AtomicInteger()
    // 計らない: サービスの JIT と接続のプールを温める
    warmUp(requests = minOf(200, requests), concurrency = concurrency, run = "$run-warmup")
    Prometheus.startWindow(report)
    val pool = Executors.newFixedThreadPool(concurrency)
    val started = System.nanoTime()
    repeat(concurrency) {
        pool.execute {
            var i = next.getAndIncrement()
            while (i < requests) {
                load.one(i)
                i = next.getAndIncrement()
            }
        }
    }
    pool.shutdown()
    check(pool.awaitTermination(1, TimeUnit.HOURS)) { "負荷の送信が終わりません" }
    val seconds = (System.nanoTime() - started) / 1e9
    report.value("INT-SALES-001 requests", "POST+GET ${requests}組 / 並列 $concurrency / ${"%.1f".format(seconds)}s")
    report.value("INT-SALES-001 throughput (req/s)", "%.1f".format(requests * 2 / seconds))
    report.value("INT-SALES-001 status", load.statuses.json())
    report.latency("INT-SALES-001 POST (client)", load.post.summary())
    report.latency("INT-SALES-001 GET (client)", load.get.summary())
    report.value("INT-SALES-001 POST > 500ms ratio", "%.4f".format(load.post.ratioAbove(500.0)))
    report.value("INT-SALES-001 GET > 500ms ratio", "%.4f".format(load.get.ratioAbove(500.0)))
    return load.created
}

/** 注文の作成と参照の 1 組の送信と、その結果。 */
private class OrderLoad(
    private val run: String,
) {
    val post = Latencies()
    val get = Latencies()
    val statuses = Counts()
    val created: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun one(i: Int) {
        val correlationId = "$run-$i"
        val postRequest =
            E2eEnvironment
                .request(ORDERS)
                .header("Idempotency-Key", "$run-${UUID.randomUUID()}")
                .header("Content-Type", "application/json")
                .header("X-Correlation-Id", correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(ORDER_BODY))
        val postStart = System.nanoTime()
        val response = orNullOnIoError { E2eEnvironment.send(postRequest) }
        post.addNanos(System.nanoTime() - postStart)
        statuses.add("POST ${response?.statusCode() ?: "io-error"}")
        val id = response?.takeIf { it.statusCode() == 201 }?.let { ORDER_ID.find(it.body())?.groupValues?.get(1) } ?: return
        created += response.header("X-Correlation-Id") ?: correlationId
        val getRequest = E2eEnvironment.request("$ORDERS/$id").GET()
        val getStart = System.nanoTime()
        val read = orNullOnIoError { E2eEnvironment.send(getRequest) }
        get.addNanos(System.nanoTime() - getStart)
        statuses.add("GET ${read?.statusCode() ?: "io-error"}")
    }
}

/**
 * Saga の各段の遅延(ADR-0048 §2)。前の段のメッセージの `ce_time`(送り手が Outbox に書いた時刻)から、受け手が処理して次の段の
 * メッセージを Outbox に書いた時刻までを、その連携の「Event E2E」とする(発行・配送・消費・処理を含む)。
 */
internal fun analyzeSaga(
    report: Report,
    correlations: Set<String>,
    sinceMillis: Long,
) {
    val topics =
        listOf(
            "sales.order.created.v1",
            "sales.order.status-changed.v1",
            "inventory.stock.cmd-reserve.v1",
            "inventory.stock.reserved.v1",
            "payment.payment.cmd-authorize.v1",
            "payment.payment.authorized.v1",
            "shipping.shipment.cmd-arrange.v1",
            "shipping.shipment.shipped.v1",
        )
    // Correlation ID → トピック → ce_time の一覧(状態の変更は 1 注文に 2 件: CONFIRMED と SHIPPED)
    val times = ConcurrentHashMap<String, MutableMap<String, MutableList<Long>>>()
    val publish = topics.associateWith { Latencies() }
    val completed = AtomicInteger()
    val seen = Counts()
    val sample = java.util.concurrent.CopyOnWriteArrayList<String>()
    Events.read(topics, sinceMillis, ReadLimits(Duration.ofMinutes(10), Duration.ofSeconds(60)), done = {
        completed.get() >= correlations.size
    }) { record ->
        seen.add(record.topic())
        if (sample.size < 3) sample += Events.header(record, "correlationid").toString()
        val correlation = Events.header(record, "correlationid")?.takeIf { it in correlations } ?: return@read
        val ceTime = Events.ceTime(record) ?: return@read
        publish.getValue(record.topic()).addMillis((record.timestamp() - ceTime).toDouble())
        val byTopic = times.computeIfAbsent(correlation) { ConcurrentHashMap() }
        val list = byTopic.computeIfAbsent(record.topic()) { mutableListOf() }
        list += ceTime
        if (record.topic() == "sales.order.status-changed.v1" && list.size == 2) completed.incrementAndGet()
    }
    report.value("saga records read", seen.json())
    report.value("saga correlation sample", "読んだ ${sample.joinToString()} / 送った ${correlations.take(3).joinToString()}")
    report.value("saga orders", "${correlations.size} 件中 ${completed.get()} 件が SHIPPED まで届いた")
    topics.forEach { report.latency("publish $it (Outbox → Kafka)", publish.getValue(it).summary()) }
    reportHops(report, times)
}

/** Saga の段ごとの遅延と、全体(作成 → SHIPPED)。[times] は Correlation ID → トピック → ce_time。 */
private fun reportHops(
    report: Report,
    times: Map<String, Map<String, List<Long>>>,
) {
    val hops =
        listOf(
            Triple("INT-INVENTORY-001 (cmd-reserve → reserved)", "inventory.stock.cmd-reserve.v1", "inventory.stock.reserved.v1"),
            Triple("INT-INVENTORY-002 (reserved → cmd-authorize)", "inventory.stock.reserved.v1", "payment.payment.cmd-authorize.v1"),
            Triple("INT-PAYMENT-001 (cmd-authorize → authorized)", "payment.payment.cmd-authorize.v1", "payment.payment.authorized.v1"),
            Triple("INT-PAYMENT-002 (authorized → cmd-arrange)", "payment.payment.authorized.v1", "shipping.shipment.cmd-arrange.v1"),
            Triple("INT-SHIPPING-001 (cmd-arrange → shipped)", "shipping.shipment.cmd-arrange.v1", "shipping.shipment.shipped.v1"),
            Triple("INT-SHIPPING-002 (shipped → status SHIPPED)", "shipping.shipment.shipped.v1", "sales.order.status-changed.v1"),
        )
    hops.forEach { (name, from, to) ->
        val samples = Latencies()
        times.values.forEach { byTopic ->
            val start = byTopic[from]?.minOrNull() ?: return@forEach
            // 状態の変更は、出荷の後に書いたもの(SHIPPED)を使う
            val end = byTopic[to]?.filter { it >= start }?.maxOrNull() ?: return@forEach
            samples.addMillis((end - start).toDouble())
        }
        report.latency(name, samples.summary())
    }
    val total = Latencies()
    times.values.forEach { byTopic ->
        val start = byTopic["sales.order.created.v1"]?.minOrNull() ?: return@forEach
        val end = byTopic["sales.order.status-changed.v1"]?.takeIf { it.size >= 2 }?.maxOrNull() ?: return@forEach
        total.addMillis((end - start).toDouble())
    }
    report.latency("Saga total (created → status SHIPPED)", total.summary())
}

/** Gateway で測った分位と誤り(Prometheus。計測の窓の間)。 */
internal fun gatewayView(
    report: Report,
    route: String,
    window: String,
) {
    listOf(0.5, 0.99).forEach { q ->
        val value =
            Prometheus.scalar(
                "histogram_quantile($q, sum by (le) (increase(apisix_http_latency_bucket{route=\"$route\",type=\"request\"}[$window])))",
            )
        report.value("gateway $route p${(q * 100).toInt()} (ms, histogram)", value?.let { "%.1f".format(it) } ?: "なし")
    }
    val errors = Prometheus.scalar("sum(increase(apisix_http_status{route=\"$route\",code=~\"5..\"}[$window]))") ?: 0.0
    val all = Prometheus.scalar("sum(increase(apisix_http_status{route=\"$route\"}[$window]))") ?: 0.0
    report.value("gateway $route 5xx / all", "%.0f / %.0f".format(errors, all))
}

/** 計測の窓の間の `caller_deadline` の割合(依存先ごと。ADR-0045 §4 の式と同じ分子・分母)。 */
internal fun callerDeadline(
    report: Report,
    window: String,
    prefix: String = "",
) {
    val attempts =
        Prometheus
            .query("sum by (job, eia_dependency_name) (increase(eia_resilience_attempts_total[$window]))")
            .associate { (labels, value) -> (labels["job"].orEmpty() + "/" + labels["eia_dependency_name"].orEmpty()) to value }
    val cut =
        Prometheus
            .query("sum by (job, eia_dependency_name) (increase(eia_resilience_timeouts_total{kind=\"caller_deadline\"}[$window]))")
            .associate { (labels, value) -> (labels["job"].orEmpty() + "/" + labels["eia_dependency_name"].orEmpty()) to value }
    attempts.filterValues { it > 0 }.toSortedMap().forEach { (key, total) ->
        val c = cut[key] ?: 0.0
        report.value("${prefix}caller_deadline $key", "%.0f / %.0f (%.4f%%)".format(c, total, c / total * 100))
    }
}

/** 計らずに注文の作成と参照を送る(結果は見ない)。 */
private fun warmUp(
    requests: Int,
    concurrency: Int,
    run: String,
) {
    val pool = Executors.newFixedThreadPool(concurrency)
    repeat(requests) { i ->
        pool.execute {
            orNullOnIoError {
                E2eEnvironment.send(
                    E2eEnvironment
                        .request(ORDERS)
                        .header("Idempotency-Key", "$run-${UUID.randomUUID()}")
                        .header("Content-Type", "application/json")
                        .header("X-Correlation-Id", "$run-$i")
                        .POST(HttpRequest.BodyPublishers.ofString(ORDER_BODY)),
                )
            }
        }
    }
    pool.shutdown()
    pool.awaitTermination(10, TimeUnit.MINUTES)
}

/** 通信の失敗(接続の拒否・タイムアウト)は null(件数に数える)。 */
internal fun <T> orNullOnIoError(block: () -> T): T? =
    try {
        block()
    } catch (e: java.io.IOException) {
        System.err.println("  I/O の失敗: ${e.javaClass.simpleName}")
        null
    }
