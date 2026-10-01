@file:Suppress("MagicNumber") // HTTP の状態コード・待ち時間・件数

package io.eia.tests.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLContext

/**
 * E2E の接続先(ローカル基盤の公開されたエンドポイント。infra/local/README.md のポート一覧)と、共通の HTTP の部品。
 * 秘密情報は環境変数から読む(`make e2e` が infra/local/.env から渡す)。
 */
internal object E2eEnvironment {
    const val GATEWAY = "http://localhost:19080"
    const val ORDERS = "$GATEWAY/sales/v1/orders"
    const val ORDER_SERVICE_TLS = "https://localhost:19443"
    private const val KEYCLOAK = "http://localhost:19180"
    const val TEMPO = "http://localhost:19320"
    const val PROMETHEUS = "http://localhost:19090"
    const val GRAFANA = "http://localhost:19300"
    const val OK = 200
    const val TOO_MANY_REQUESTS = 429
    private const val MAX_RATE_LIMIT_WAITS = 3

    // 下の http などより先に初期化する(object の初期化はファイルの上から順)
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

    /** client-credentials のクライアント(infra/local/keycloak/realm-eiaf.json)。 */
    enum class Client(
        val id: String,
        val secretVariable: String,
    ) {
        A("eiaf-e2e", "EIAF_E2E_CLIENT_SECRET"),
        B("eiaf-e2e-b", "EIAF_E2E_B_CLIENT_SECRET"),
    }

    /** 開発用の証明書(make certs。infra/local/certs)。 */
    val certs: Path = Path.of(System.getProperty("eiaf.repo.root", ".")).resolve("infra/local/certs")

    val http: HttpClient = client(null)

    fun client(sslContext: SSLContext?): HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .apply { sslContext?.let(::sslContext) }
            .build()

    fun secret(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("$name がありません。make e2e で実行してください(infra/local/.env を読み込みます)")

    private val tokens = mutableMapOf<Client, String>()

    /** `sales.order:read` / `sales.order:write` のスコープのアクセストークン(テストの間は使い回す。寿命は 5 分)。 */
    @Synchronized
    fun token(client: Client = Client.A): String =
        tokens.getOrPut(client) {
            val form =
                mapOf(
                    "grant_type" to "client_credentials",
                    "client_id" to client.id,
                    "client_secret" to secret(client.secretVariable),
                    "scope" to "sales.order:read sales.order:write",
                ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
            val response =
                send(
                    HttpRequest
                        .newBuilder(URI.create("$KEYCLOAK/realms/eiaf/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)),
                )
            check(response.statusCode() == OK) { "${client.id} のトークンを取れません(${response.statusCode()})" }
            json(response.body())
                .jsonObject
                .getValue("access_token")
                .jsonPrimitive.content
        }

    fun request(
        url: String,
        client: Client? = Client.A,
    ): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .apply { client?.let { header("Authorization", "Bearer ${token(it)}") } }

    fun send(
        builder: HttpRequest.Builder,
        on: HttpClient = http,
    ): HttpResponse<String> = on.send(builder.build(), HttpResponse.BodyHandlers.ofString())

    /**
     * Rate Limit(429)に当たったら Retry-After だけ待って送り直す。Rate Limit そのものを確かめるテスト以外で使う
     * (直前に make verify などで枠を使い切っていても、ほかのシナリオが失敗しないようにする)。
     */
    fun sendRespectingRateLimit(builder: HttpRequest.Builder): HttpResponse<String> {
        repeat(MAX_RATE_LIMIT_WAITS) {
            val response = send(builder)
            if (response.statusCode() != TOO_MANY_REQUESTS) return response
            val seconds = response.header("Retry-After")?.toLongOrNull() ?: 1
            Thread.sleep(Duration.ofSeconds(seconds).toMillis())
        }
        return send(builder)
    }

    fun get(url: String): String = send(HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)).body()

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    fun basic(
        user: String,
        password: String,
    ): String = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

/** 注文の受け付けの本文(1 明細)。 */
internal const val ORDER_BODY =
    """{"customerId":"cust-e2e",""" +
        """"lines":[{"productId":"prod-1","sku":"SKU-1","quantity":1,"unitPrice":{"amount":"100","currency":"JPY"}}],""" +
        """"shippingAddress":{"countryCode":"JP","postalCode":"100-0001","city":"Chiyoda","line1":"1-1"}}"""

internal fun HttpResponse<*>.header(name: String): String? = headers().firstValue(name).orElse(null)

/** [timeout] の間、[block] が null 以外を返すまで [interval] ごとにやり直す。 */
internal fun <T : Any> eventually(
    timeout: Duration = Duration.ofSeconds(90),
    interval: Duration = Duration.ofSeconds(3),
    block: () -> T?,
): T? {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (true) {
        block()?.let { return it }
        if (System.nanoTime() > deadline) return null
        Thread.sleep(interval.toMillis())
    }
}
