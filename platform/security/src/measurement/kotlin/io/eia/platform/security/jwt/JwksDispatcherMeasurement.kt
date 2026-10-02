@file:Suppress("MagicNumber") // 計測の条件(同時数・遅延・時間)

package io.eia.platform.security.jwt

import com.sun.net.httpserver.HttpServer
import io.eia.shared.kernel.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/*
 * JWKS の取得をどの dispatcher で行うかの計測(P05 ⑨。手順と結果は docs/reports/p05-jwks-dispatcher.md)。
 *
 * IdP(JWKS のサーバ)を遅くした状態で、N 件のトークンの検証と、DB の処理を模した M 件のブロッキングの処理
 * (order-service の Exposed と同じく Dispatchers.IO で動く)を同時に流し、DB の処理の待ち時間を方式ごとに比べる。
 * JWKS の取得は、本番と同じ Nimbus の DefaultResourceRetriever(HttpURLConnection)で、実際の HTTP で行う。
 *
 * 条件はシステムプロパティで変えられる: -Pjwks.verifications=200 -Pjwks.dbTasks=100 -Pjwks.dbWorkMs=20 -Pjwks.repeat=3
 */

private val VERIFICATIONS = Integer.getInteger("jwks.verifications", 200)
private val DB_TASKS = Integer.getInteger("jwks.dbTasks", 100)
private val DB_WORK_MS = Integer.getInteger("jwks.dbWorkMs", 20).toLong()
private val REPEAT = Integer.getInteger("jwks.repeat", 3)

/** キャッシュの期限を短くした設定(期限切れを待てるように)。そのほかは本番の既定値(ADR-0019 §3)。 */
private val CACHE_TTL: Duration = 20.seconds
private val JWKS =
    JwksConfig(
        cacheTtl = CACHE_TTL,
        refreshAhead = 1.seconds,
        refreshTimeout = 15.seconds,
        rateLimitMinInterval = 1.seconds,
        outageTolerance = 15.minutes,
    )

/** 応答を遅らせられる JWKS のサーバ。 */
private class SlowJwksServer : AutoCloseable {
    val delayMillis = AtomicLong(0)
    val fetches = AtomicInteger()
    private val body = TestKeys.jwks(TestKeys.rsa).toString(true).toByteArray()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/jwks") { exchange ->
                fetches.incrementAndGet()
                Thread.sleep(delayMillis.get())
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    val uri: URI = URI.create("http://127.0.0.1:${server.address.port}/jwks")

    override fun close() = server.stop(0)
}

private enum class Scenario(
    val label: String,
    val idpDelay: Duration,
    val expired: Boolean,
) {
    WARM("キャッシュが有効(IdP は速い)", Duration.ZERO, false),
    EXPIRED_SLOW("キャッシュの期限切れ + IdP が 1.5 秒で応答", 1500.milliseconds, true),
    EXPIRED_HANG("キャッシュの期限切れ + IdP が応答しない(読み取りのタイムアウト 2 秒)", 30.seconds, true),
}

private class Strategy(
    val label: String,
    val dispatcher: CoroutineDispatcher,
)

private data class Stats(
    val p50: Long,
    val p99: Long,
    val max: Long,
) {
    override fun toString() = "$p50 / $p99 / $max"
}

private fun stats(millis: List<Long>): Stats {
    val sorted = millis.sorted()

    fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
    return Stats(at(0.5), at(0.99), sorted.last())
}

private data class Row(
    val verify: Stats,
    val db: Stats,
    val ok: Int,
    val unavailable: Int,
    val fetches: Int,
)

private suspend fun measure(
    strategy: Strategy,
    scenario: Scenario,
): Row {
    SlowJwksServer().use { idp ->
        JwtVerifier(JwtVerifierConfig(ISSUER, AUDIENCE, idp.uri, jwks = JWKS)).use { verifier ->
            val token = signRsa(validClaims(now = Clock.System.now()).build(), key = TestKeys.rsa)
            check(verifier.verifyBlocking(token) is Result.Ok) { "準備の検証に失敗しました" }
            if (scenario.expired) delay(CACHE_TTL + 1.seconds) // 期限切れを待つ(この間は要求を送らず、裏の取り直しも起こさない)
            idp.delayMillis.set(scenario.idpDelay.inWholeMilliseconds)
            idp.fetches.set(0)
            run {
                val (verifications, db) =
                    coroutineScope {
                        val verifications =
                            (1..VERIFICATIONS).map {
                                async {
                                    val start = TimeSource.Monotonic.markNow()
                                    val result = withContext(strategy.dispatcher) { verifier.verifyBlocking(token) }
                                    start.elapsedNow().inWholeMilliseconds to result
                                }
                            }
                        // 検証が先にスレッドを取った後に、DB の処理が来る
                        delay(50)
                        val db =
                            (1..DB_TASKS).map {
                                async {
                                    val start = TimeSource.Monotonic.markNow()
                                    withContext(Dispatchers.IO) { Thread.sleep(DB_WORK_MS) }
                                    start.elapsedNow().inWholeMilliseconds
                                }
                            }
                        verifications.awaitAll() to db.awaitAll()
                    }
                return Row(
                    verify = stats(verifications.map { it.first }),
                    db = stats(db),
                    ok = verifications.count { it.second is Result.Ok },
                    unavailable = verifications.count { (it.second as? Result.Err)?.error == JwtVerificationError.KeysUnavailable },
                    fetches = idp.fetches.get(),
                )
            }
        }
    }
}

/**
 * ピン留めの検出(-Djdk.tracePinnedThreads=full)が働いていることの対照。仮想スレッドで synchronized の中で sleep すると、
 * JDK 21 はスタックトレース(`<== monitors:1`)を出す。計測の本体の後に実行し、出力の位置で区別する。
 */
private fun pinningControl(virtual: java.util.concurrent.ExecutorService) {
    val lock = Any()
    println("--- ピン留めの対照: 開始(ここから終了までのスタックトレースは、意図して起こしたもの) ---")
    virtual.submit { synchronized(lock) { Thread.sleep(20) } }.get()
    println("--- ピン留めの対照: 終了 ---")
}

fun main() {
    val virtual = Executors.newVirtualThreadPerTaskExecutor()
    val strategies =
        listOf(
            Strategy("A: 共有の Dispatchers.IO(今)", Dispatchers.IO),
            Strategy("B: Dispatchers.IO.limitedParallelism(16)", Dispatchers.IO.limitedParallelism(16)),
            Strategy("D: 仮想スレッド", virtual.asCoroutineDispatcher()),
        )
    println(
        "条件: 検証 $VERIFICATIONS 件 + DB の処理 $DB_TASKS 件(各 $DB_WORK_MS ms のブロッキング。Dispatchers.IO)を同時に。" +
            "繰り返し $REPEAT 回。JDK ${Runtime.version()}、CPU ${Runtime.getRuntime().availableProcessors()}",
    )
    println("| 状況 | 方式 | 回 | DB の処理の待ち p50 / p99 / max (ms) | 検証 p50 / p99 / max (ms) | 成功 | 503 | JWKS の取得 |")
    println("|---|---|---|---|---|---|---|---|")
    runBlocking {
        for (scenario in Scenario.entries) {
            for (strategy in strategies) {
                repeat(REPEAT) { round ->
                    val row = measure(strategy, scenario)
                    println(
                        "| ${scenario.label} | ${strategy.label} | ${round + 1} | ${row.db} | ${row.verify} | " +
                            "${row.ok} | ${row.unavailable} | ${row.fetches} |",
                    )
                }
            }
        }
    }
    pinningControl(virtual)
    virtual.close()
}
