@file:Suppress("MagicNumber") // 分位・時間の単位の換算

package io.eia.tests.e2e.nfr

import io.eia.tests.e2e.E2eEnvironment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/** 遅延の分位(ミリ秒)。分位は nearest-rank(件数が少ないときは p99 = 最大に近い値になる)。 */
internal data class Summary(
    val count: Int,
    val p50: Double,
    val p95: Double,
    val p99: Double,
    val max: Double,
) {
    fun line(): String = "n=%d p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms".format(count, p50, p95, p99, max)

    fun json(): JsonObject =
        JsonObject(
            mapOf(
                "count" to JsonPrimitive(count),
                "p50Ms" to JsonPrimitive(p50),
                "p95Ms" to JsonPrimitive(p95),
                "p99Ms" to JsonPrimitive(p99),
                "maxMs" to JsonPrimitive(max),
            ),
        )
}

/** 遅延の標本(ミリ秒)。複数のスレッドから足せる。 */
internal class Latencies {
    private val values = ConcurrentLinkedQueue<Double>()

    fun addNanos(nanos: Long) {
        values += nanos / 1_000_000.0
    }

    fun addMillis(millis: Double) {
        values += millis
    }

    /** [limitMs] を超えた標本の割合。 */
    fun ratioAbove(limitMs: Double): Double = if (values.isEmpty()) 0.0 else values.count { it > limitMs }.toDouble() / values.size

    fun summary(): Summary? {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return null

        fun at(q: Double) = sorted[(ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        return Summary(sorted.size, at(0.50), at(0.95), at(0.99), sorted.last())
    }
}

/** 結果の件数(状態コードなど)。 */
internal class Counts {
    private val byKey = ConcurrentHashMap<String, AtomicInteger>()

    fun add(key: String) {
        byKey.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
    }

    fun get(key: String): Int = byKey[key]?.get() ?: 0

    fun total(): Int = byKey.values.sumOf { it.get() }

    fun json(): JsonObject = JsonObject(byKey.toSortedMap().mapValues { (_, value) -> JsonPrimitive(value.get()) })
}

/** 計測の結果。標準出力に出し、`tests/e2e/build/nfr/{シナリオ}-{時刻}.json` に書く(レポートに書き写す元)。 */
internal class Report(
    private val scenario: String,
) {
    private val entries = linkedMapOf<String, JsonElement>()
    val startedAt: Instant = Instant.now()

    /** Prometheus で見る窓の始まり(温める送信の後)。 */
    var windowStart: Instant = startedAt

    fun latency(
        name: String,
        summary: Summary?,
    ) {
        println("  $name: ${summary?.line() ?: "標本なし"}")
        entries[name] = summary?.json() ?: JsonPrimitive("no samples")
    }

    fun value(
        name: String,
        value: Any,
    ) {
        println("  $name: $value")
        entries[name] =
            when (value) {
                is Number -> JsonPrimitive(value)
                is JsonElement -> value
                else -> JsonPrimitive(value.toString())
            }
    }

    fun write() {
        val out = Path.of(System.getProperty("eiaf.nfr.out", "build/nfr"))
        Files.createDirectories(out)
        val file = out.resolve("$scenario-${startedAt.epochSecond}.json")
        val body =
            JsonObject(
                mapOf(
                    "scenario" to JsonPrimitive(scenario),
                    "startedAt" to JsonPrimitive(startedAt.toString()),
                    "finishedAt" to JsonPrimitive(Instant.now().toString()),
                    "results" to JsonObject(entries),
                ),
            )
        Files.writeString(file, body.toString())
        println("結果: $file")
    }
}

/** Prometheus の瞬時のクエリ(公開されたエンドポイント)。系列ごとのラベルと値。 */
internal object Prometheus {
    fun query(expr: String): List<Pair<Map<String, String>, Double>> {
        val body = E2eEnvironment.get("${E2eEnvironment.PROMETHEUS}/api/v1/query?query=${E2eEnvironment.encode(expr)}")
        val result =
            E2eEnvironment
                .json(body)
                .jsonObject
                .getValue("data")
                .jsonObject
                .getValue("result")
                .jsonArray
        return result.map { item ->
            val series = item.jsonObject
            val labels = series.getValue("metric").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
            val value = (series.getValue("value") as JsonArray)[1].jsonPrimitive.content
            labels to value.toDouble()
        }
    }

    /** 1 つの値にまとまるクエリの値。系列がなければ null。 */
    fun scalar(expr: String): Double? = query(expr).firstOrNull()?.second?.takeUnless { it.isNaN() }

    /** 次の scrape(15 秒間隔)で計測の終わりまでの値を取り込むまで待つ。 */
    fun awaitScrape() {
        Thread.sleep(35_000)
    }

    /**
     * 計測の窓を始める。慣らしの送信で作られた系列を Prometheus が 1 回 scrape してから、[Report.windowStart] を今にする。
     * 作り直した直後の Gateway(枠を上げるため)は系列が 0 から始まり、窓の前の値がないと `increase` が窓の中の増分を取りこぼす。
     */
    fun startWindow(report: Report) {
        awaitScrape()
        report.windowStart = Instant.now()
    }

    /** 計測の窓([from] から今まで。秒。scrape の分の余裕を足す)。 */
    fun window(from: Instant): String =
        "${java.time.Duration
            .between(from, Instant.now())
            .seconds + 15}s"
}
