package io.eia.platform.security.token

import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Client Credentials Grant でアクセストークンを取得し、期限の少し前まで再利用する(Framework 12.1。ADR-0019 §4)。
 *
 * - **再利用**: 期限の [ClientCredentialsConfig.refreshBefore](既定 30 秒)前まで同じトークンを返す。
 *   寿命が短いトークンでは寿命の 10% との小さい方を使う。
 * - **同時の取得を 1 本にまとめる**: 取得は [Mutex] の中で 1 つだけ走らせる。待っていた呼び出しは、ロックを取った後に
 *   待っている間に終わった取得の結果を使う(成功ならキャッシュ、失敗ならその失敗)。同時に 100 回呼ばれても要求は 1 回になり、
 *   IdP の障害中に待ち行列の全員が順に取り直して待たされることもない。期限内のトークンがあれば、取得中の呼び出しを待たずに返す。
 * - **タイムアウト**: 1 回の取得(接続から本文の読み取りまで)を [ClientCredentialsConfig.timeout](既定 5 秒)で打ち切る。
 * - **失敗**: 失敗はキャッシュしない。期限前の取り直しに失敗し、期限内のトークンがあれば、それを返して WARN を残す。
 *   次の取り直しは [ClientCredentialsConfig.refreshRetryInterval] の後にする(失敗のたびに全呼び出しを待たせないため)。
 * - **Retry と Circuit Breaker は P04b で結線する**(`platform/reliability`)。ここでは 1 回だけ要求し、
 *   失敗を [TokenError] の Retryable / NonRetryable(429 / 503 は `Retry-After` を retryAfter に入れる)で返す。
 * - **クライアントの認証**: `client_secret_basic`。RFC 6749 §2.3.1 のとおり、ID と Secret を
 *   application/x-www-form-urlencoded でエンコードしてから Base64 にする。Secret は取得のたびに [SecretProvider] から読む。
 * - 応答の本文・Client Secret・トークンはログにも [TokenError] にも入れない。ログに残すのは、ステータスと、
 *   形式を検証した OAuth のエラーコードだけ。
 *
 * [httpClient] は呼び出し側が用意する(`ClientObservability` で traceparent と Correlation ID を付けられる)。
 * リクエストやヘッダをログに出すプラグイン(Ktor の Logging など)は付けない(Authorization が漏れるため)。
 *
 * @param clock 期限の判定に使う時刻(テストでは進められる時計を渡す)
 */
public class ClientCredentialsTokenProvider(
    private val config: ClientCredentialsConfig,
    private val httpClient: HttpClient,
    private val secrets: SecretProvider,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: Cached? = null

    /** 期限前の取り直しに失敗した後、次に取り直してよい時刻。 */
    @Volatile
    private var retryNotBefore: Instant? = null

    /** 終わった取得の回数と、最後の取得の失敗(成功なら null)。ロックを待つ間に終わった取得の結果を共有するために使う。 */
    @Volatile
    private var completedFetches: Long = 0
    private var lastFailure: TokenError? = null

    /** 期限内のトークンを返す。なければ取得する。 */
    @Suppress("ReturnCount") // ロックを取らずに返せる場合を先に返す
    public suspend fun token(): Result<AccessToken, TokenError> {
        cached?.let { current ->
            val now = clock.now()
            if (now < current.refreshAt) return ok(current.token)
            // 期限前の取り直しの時間帯: ほかの呼び出しが取得中か、失敗の直後なら、待たずに期限内のトークンを返す
            if (current.isValidAt(now) && (mutex.isLocked || isRetryDeferred(now))) return ok(current.token)
        }
        val seen = completedFetches
        return mutex.withLock { if (completedFetches != seen) coalesced() ?: refreshLocked() else refreshLocked() }
    }

    /**
     * 下流が [token] を 401 で拒否したときに呼ぶ(IdP 側で失効させられた場合など)。次の [token] で取り直す。
     * キャッシュが既に別のトークンに替わっていれば何もしない(取り直したばかりのトークンを捨てないため)。
     */
    public suspend fun invalidate(token: AccessToken) {
        mutex.withLock {
            if (cached?.token === token) {
                cached = null
                retryNotBefore = null
            }
        }
    }

    /**
     * ロックを待っている間に取得が終わっていた場合の結果。期限内のトークンがあればそれを、最後の取得が失敗していればその失敗を返す
     * (取り直さない)。どちらでもなければ null(期限のないトークンを取得した場合など)。
     */
    private fun coalesced(): Result<AccessToken, TokenError>? {
        val current = cached
        if (current != null && current.isValidAt(clock.now())) return ok(current.token)
        return lastFailure?.let { err(it) }
    }

    @Suppress("ReturnCount") // 取り直さずに返せる場合を先に返す
    private suspend fun refreshLocked(): Result<AccessToken, TokenError> {
        val current = cached
        val now = clock.now()
        if (current != null && now < current.refreshAt) return ok(current.token)
        if (current != null && current.isValidAt(now) && isRetryDeferred(now)) return ok(current.token)

        val fetched = fetch()
        completedFetches++
        lastFailure = (fetched as? Result.Err)?.error
        return when (fetched) {
            is Result.Ok -> {
                val token = fetched.value
                cached = token.expiresAt?.let { Cached(token, refreshAt(requestedAt = now, expiresAt = it)) }
                retryNotBefore = null
                ok(token)
            }

            is Result.Err -> {
                val after = clock.now()
                if (current != null && current.isValidAt(after)) {
                    retryNotBefore = after + config.refreshRetryInterval
                    logger.warn(
                        "期限前のトークンの取り直しに失敗したため、期限内のトークンを使い続けます code={} expiresAt={}",
                        fetched.error.code,
                        current.token.expiresAt,
                    )
                    ok(current.token)
                } else {
                    cached = null
                    logger.warn("トークンを取得できません code={} {}", fetched.error.code, fetched.error.message)
                    fetched
                }
            }
        }
    }

    private fun isRetryDeferred(now: Instant): Boolean = retryNotBefore?.let { now < it } ?: false

    /** 期限の手前で取り直す時刻。寿命の 10% と [ClientCredentialsConfig.refreshBefore] の小さい方だけ前にする。 */
    private fun refreshAt(
        requestedAt: Instant,
        expiresAt: Instant,
    ): Instant {
        val lifetime = expiresAt - requestedAt
        val margin = minOf(config.refreshBefore, lifetime / REFRESH_LIFETIME_DIVISOR)
        return expiresAt - margin
    }

    @Suppress("ReturnCount") // Secret の取得・送信・タイムアウトのそれぞれの失敗で返す
    private suspend fun fetch(): Result<AccessToken, TokenError> {
        val secret =
            when (val result = secrets.get(config.clientSecret)) {
                is Result.Ok -> result.value
                is Result.Err -> return err(ClientSecretUnavailable.of(result.error))
            }
        // 期限は要求を送る前の時刻から数える(受信までの時間の分だけ早めに見積もり、期限切れのトークンを使わない側に倒す)
        val requestedAt = clock.now()
        val exchange =
            try {
                withTimeoutOrNull(config.timeout) { send(secret) }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // 接続の失敗など。例外のメッセージは URL やヘッダを含みうるため、型の名前だけを残す
                logger.debug("トークンエンドポイントに接続できません exception={}", e::class.qualifiedName)
                return err(TokenEndpointUnavailable("connection"))
            } ?: return err(TokenEndpointUnavailable("timeout"))
        return interpret(exchange, requestedAt)
    }

    private suspend fun send(secret: Secret): Exchange {
        val response: HttpResponse =
            httpClient.submitForm(
                url = config.tokenEndpoint.toString(),
                formParameters =
                    parameters {
                        append("grant_type", "client_credentials")
                        if (config.scopes.isNotEmpty()) append("scope", config.scopes.sorted().joinToString(" "))
                    },
            ) {
                expectSuccess = false
                accept(ContentType.Application.Json)
                header(HttpHeaders.Authorization, basicAuthorization(config.clientId, secret))
            }
        val body = response.bodyAsChannel().readBuffer(MAX_BODY_BYTES + 1L).readByteArray()
        return Exchange(response.status, response.headers[HttpHeaders.RetryAfter], body)
    }

    @Suppress("MagicNumber") // HTTP のステータスコード
    private fun interpret(
        exchange: Exchange,
        requestedAt: Instant,
    ): Result<AccessToken, TokenError> {
        val status = exchange.status.value
        return when {
            status == HttpStatusCode.OK.value -> {
                TokenResponse.parse(exchange.body, requestedAt, config.scopes)
            }

            status == HttpStatusCode.TooManyRequests.value -> {
                err(TokenEndpointUnavailable("rate_limited", status, RetryAfter.parse(exchange.retryAfter, clock.now())))
            }

            status == HttpStatusCode.RequestTimeout.value -> {
                err(TokenEndpointUnavailable("request_timeout", status))
            }

            status >= 500 -> {
                err(TokenEndpointUnavailable("server_error", status, RetryAfter.parse(exchange.retryAfter, clock.now())))
            }

            else -> {
                err(TokenRequestRejected(status, TokenResponse.oauthErrorCode(exchange.body)))
            }
        }
    }

    private class Cached(
        val token: AccessToken,
        val refreshAt: Instant,
    ) {
        fun isValidAt(now: Instant): Boolean = token.expiresAt?.let { now < it } ?: false
    }

    private class Exchange(
        val status: HttpStatusCode,
        val retryAfter: String?,
        val body: ByteArray,
    )

    public companion object {
        /** 応答の本文の大きさの上限。これを超える応答は解析しない。 */
        public const val MAX_BODY_BYTES: Int = TokenResponse.MAX_BODY_BYTES

        private const val REFRESH_LIFETIME_DIVISOR = 10
        private val logger = LoggerFactory.getLogger(ClientCredentialsTokenProvider::class.java)

        /**
         * `client_secret_basic` の `Authorization` の値(RFC 6749 §2.3.1)。
         * ID と Secret を application/x-www-form-urlencoded でエンコードしてから `id:secret` を Base64 にする
         * (Secret に `:` を含んでも区切りと混同されない)。
         */
        internal fun basicAuthorization(
            clientId: String,
            secret: Secret,
        ): String {
            val credentials = formEncode(clientId) + ":" + formEncode(secret.reveal())
            return "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
        }

        private fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
    }
}
