@file:Suppress("MagicNumber") // HTTP の成功の状態コード

package io.eia.tests.e2e

import io.eia.tests.e2e.E2eEnvironment.send
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/**
 * 障害の注入(Toxiproxy の管理 API。ADR-0044)。接続先を切り替えるのは `make up ... E2E=1` のときだけ(tests/e2e/e2e.env)。
 * [whileDelayed]・[whileCut] は、失敗しても必ず元に戻す(後のシナリオを止めない)。
 */
internal object Toxiproxy {
    /** order-service → PostgreSQL。 */
    const val ORDER_DB = "postgres"

    /** bff-graphql → Gateway(注文の読み取り)。 */
    const val BFF_GATEWAY = "gateway-from-bff"
    private const val API = "http://localhost:19474"
    private const val TIMEOUT_SECONDS = 10L
    private val SUCCESS = 200..299

    /** [proxy] の応答を [latencyMillis] だけ遅らせている間に [block] を実行する。 */
    fun <T> whileDelayed(
        proxy: String,
        latencyMillis: Int,
        block: () -> T,
    ): T {
        post(
            "/proxies/$proxy/toxics",
            """{"name":"e2e_latency","type":"latency","stream":"downstream","attributes":{"latency":$latencyMillis}}""",
        )
        return try {
            block()
        } finally {
            reset()
        }
    }

    /** [proxy] を切断している間に [block] を実行する(既存の接続も切れ、新しい接続は拒否される)。 */
    fun <T> whileCut(
        proxy: String,
        block: () -> T,
    ): T {
        post("/proxies/$proxy", """{"enabled":false}""")
        return try {
            block()
        } finally {
            reset()
        }
    }

    /** すべての proxy を有効にし、すべての toxic を消す。 */
    fun reset() {
        post("/reset", "{}")
    }

    private fun post(
        path: String,
        body: String,
    ) {
        val response =
            send(
                HttpRequest
                    .newBuilder(URI.create("$API$path"))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)),
            )
        check(response.statusCode() in SUCCESS) {
            "Toxiproxy の $path が ${response.statusCode()} を返しました"
        }
    }
}
