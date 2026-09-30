package io.eia.platform.security.token

import io.eia.platform.reliability.HttpCallClassifier
import io.eia.platform.reliability.RetryAfter
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.AttemptTimedOut
import io.eia.shared.resilience.BulkheadFull
import io.eia.shared.resilience.CallDeadline
import io.eia.shared.resilience.CircuitOpen
import io.eia.shared.resilience.DeadlineExceeded
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URLEncoder
import java.nio.channels.UnresolvedAddressException
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Client Credentials Grant でアクセストークンを取得し、期限の少し前まで再利用する(Framework 12.1。ADR-0019 §4)。
 *
 * - **再利用**: 期限の [ClientCredentialsConfig.refreshBefore](既定 30 秒)前まで同じトークンを返す。
 *   寿命が短いトークンでは寿命の 10% との小さい方を使う。寿命は [ClientCredentialsConfig.maxCacheLifetime](既定 1 時間)で打ち切る。
 * - **同時の取得を 1 本にまとめる**: 取得は [Mutex] の中で 1 つだけ走らせる。待っていた呼び出しは、ロックを取った後に
 *   待っている間に終わった取得の結果を使う(成功ならキャッシュ、失敗ならその失敗)。同時に 100 回呼ばれても要求は 1 回になり、
 *   IdP の障害中に待ち行列の全員が順に取り直して待たされることもない。期限内のトークンがあれば、取得中の呼び出しを待たずに返す。
 * - **Timeout・Retry・Circuit Breaker**(Framework 13。ADR-0021): 1 回の取得を [resilience] の中で行う。
 *   - 1 回の試行(接続から本文の読み取りまで)は `attemptTimeout`(既定 5 秒)、リトライを含む取得全体は `deadline`(既定 10 秒)で打ち切る。
 *   - Retryable(タイムアウト・接続の失敗・408・429・5xx)はリトライし、429 / 503 の `Retry-After` を優先して待つ
 *     (kernel の RetryPolicy。上限を超える Retry-After と、締め切りを超える待ちでは打ち切る)。
 *   - NonRetryable(400・401 の `invalid_client` などの 4xx・応答の形式の不正)はリトライせず、Circuit Breaker の成功に数える。
 *   - Circuit Breaker が開いている間は IdP に要求を送らず、`circuit_open` の [TokenEndpointUnavailable] を返す。
 *   - Secret の取得は [resilience] の外で、取得ごとに 1 回だけ行う(Secret の失敗を IdP の失敗として数えないため)。
 * - **呼び出し元の締め切り**(ADR-0021 §12): 呼び出し元のコンテキストに [CallDeadline] があれば、取得の締め切りは
 *   `deadline` とその残り時間の短い方になる([resilience] が引き継ぐ)。取得中の呼び出しを待つ時間も、その残り時間までにする
 *   (待ちきれなければ `deadline_exceeded`)。呼び出し元の締め切りで打ち切った取得の失敗は、待っていた呼び出しに共有しない。
 *   その失敗は取得した呼び出しの予算によるもので、待っていた呼び出しは自分の残り時間で取り直せるため。
 * - **Fallback**: 失敗はキャッシュしない。期限前の取り直しに失敗し、期限内のトークンがあれば、それを返して WARN を残す
 *   (同期呼び出しの 4 点セットの Fallback)。次の取り直しは [ClientCredentialsConfig.refreshRetryInterval] の後にする
 *   (失敗のたびに全呼び出しを待たせないため)。
 * - **クライアントの認証**: `client_secret_basic`。RFC 6749 §2.3.1 のとおり、ID と Secret を
 *   application/x-www-form-urlencoded でエンコードしてから Base64 にする。Secret は取得のたびに [SecretProvider] から読む。
 * - 応答の本文・Client Secret・トークンはログにも [TokenError] にも入れない。ログに残すのは、ステータスと、
 *   形式を検証した OAuth のエラーコードだけ。
 *
 * [httpClient] は呼び出し側が用意する(`ClientObservability` で traceparent と Correlation ID を付けられる)。
 * リクエストやヘッダをログに出すプラグイン(Ktor の Logging など)は付けない(Authorization が漏れるため)。
 *
 * **このクラスと [resilience] は、トークンエンドポイントごとに 1 つを作って使い回す。呼び出しごとに新しい [Resilience] を作らない。**
 * Circuit Breaker とリトライバジェットは、複数回の取得にまたがる状態を持つ。取得のたびに作ると状態が捨てられ、
 * IdP の障害中も遮断されずに要求を送り続ける。
 *
 * @param clock 期限の判定に使う時刻(テストでは進められる時計を渡す)
 * @param resilience トークンエンドポイントへの取得を包む [Resilience]。すべての取得で、この 1 つを使う。
 *   メトリクスを出すときは、`ResilienceMetrics.resilience` で作ったものを渡す。既定は [DEFAULT_RESILIENCE_NAME] と [DEFAULT_RESILIENCE]
 */
public class ClientCredentialsTokenProvider(
    private val config: ClientCredentialsConfig,
    private val httpClient: HttpClient,
    private val secrets: SecretProvider,
    private val clock: Clock = Clock.System,
    private val resilience: Resilience = Resilience(DEFAULT_RESILIENCE_NAME, DEFAULT_RESILIENCE),
) {
    private val mutex = Mutex()
    private val classifier = HttpCallClassifier(clock = clock)

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
        if (!mutex.lockWithin(CallDeadline.current()?.remaining())) return err(TokenEndpointUnavailable(DEADLINE_EXCEEDED))
        return try {
            if (completedFetches != seen) coalesced() ?: refreshLocked() else refreshLocked()
        } finally {
            mutex.unlock()
        }
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

        val (fetched, shareable) = fetch()
        completedFetches++
        lastFailure = (fetched as? Result.Err)?.error?.takeIf { shareable }
        return when (fetched) {
            is Result.Ok -> {
                val token = fetched.value
                cached = token.expiresAt?.let { cache(token, requestedAt = now, expiresAt = it) }
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

    /**
     * 使い続けてよい期限を [ClientCredentialsConfig.maxCacheLifetime] で打ち切り、その手前で取り直す。
     * 取り直す時刻は、寿命の 10% と [ClientCredentialsConfig.refreshBefore] の小さい方だけ前にする。
     * 打ち切った期限は、取り直しに失敗したときに期限内のトークンを使い続ける判定にも使う(IdP の `expires_in` まで延ばさない)。
     */
    private fun cache(
        token: AccessToken,
        requestedAt: Instant,
        expiresAt: Instant,
    ): Cached {
        val until = minOf(expiresAt, requestedAt + config.maxCacheLifetime)
        val margin = minOf(config.refreshBefore, (until - requestedAt) / REFRESH_LIFETIME_DIVISOR)
        return Cached(token, refreshAt = until - margin, validUntil = until)
    }

    /**
     * Secret を読み、[resilience] の中で要求する(リトライと Circuit Breaker は [resilience] が行う)。
     * 失敗を待っていた呼び出しに共有してよいかも返す。呼び出し元の締め切り([CallDeadline])で打ち切った失敗は共有しない。
     */
    private suspend fun fetch(): Fetched {
        val secret =
            when (val result = secrets.get(config.clientSecret)) {
                is Result.Ok -> result.value
                is Result.Err -> return Fetched(err(ClientSecretUnavailable.of(result.error)))
            }
        return when (val result = resilience.execute { attempt(secret) }) {
            is Result.Ok -> {
                Fetched(result)
            }

            is Result.Err -> {
                Fetched(
                    err(result.error.toTokenError()),
                    shareable = !result.error.isCallerDeadline(resilience.config.deadline),
                )
            }
        }
    }

    /**
     * 1 回の試行。タイムアウトは [resilience] の `withTimeoutOrNull` が行う。
     * 接続の失敗とタイムアウト(Ktor の HttpTimeout など)だけを、HttpCallClassifier と同じ基準で `connection` / `timeout` にする。
     * キャンセルとそのほかの例外(プログラムの誤り)は捕まえずに伝え、Circuit Breaker とリトライに数えない(ADR-0021 §3)。
     */
    private suspend fun attempt(secret: Secret): Result<AccessToken, DomainError> {
        // 期限は要求を送る前の時刻から数える(受信までの時間の分だけ早めに見積もり、期限切れのトークンを使わない側に倒す)
        val requestedAt = clock.now()
        return try {
            when (val result = interpret(send(secret), requestedAt)) {
                is Result.Ok -> result
                is Result.Err -> err(result.error.asDomainError())
            }
        } catch (e: IOException) {
            err(unreachable(e, classifier))
        } catch (e: UnresolvedAddressException) {
            err(unreachable(e, classifier))
        }
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
                // Retry-After は 503 だけで使う(INTEGRATION_STANDARDS §3。429 は上)
                val retryAfter =
                    if (status == HttpStatusCode.ServiceUnavailable.value) RetryAfter.parse(exchange.retryAfter, clock.now()) else null
                err(TokenEndpointUnavailable("server_error", status, retryAfter))
            }

            else -> {
                err(TokenRequestRejected(status, TokenResponse.oauthErrorCode(exchange.body)))
            }
        }
    }

    private class Cached(
        val token: AccessToken,
        val refreshAt: Instant,
        val validUntil: Instant,
    ) {
        fun isValidAt(now: Instant): Boolean = now < validUntil
    }

    private data class Fetched(
        val result: Result<AccessToken, TokenError>,
        val shareable: Boolean = true,
    )

    private class Exchange(
        val status: HttpStatusCode,
        val retryAfter: String?,
        val body: ByteArray,
    )

    public companion object {
        /** 応答の本文の大きさの上限。これを超える応答は解析しない。 */
        public const val MAX_BODY_BYTES: Int = TokenResponse.MAX_BODY_BYTES

        /** 既定の [Resilience] の名前(メトリクスの `eia.dependency.name`)。 */
        public const val DEFAULT_RESILIENCE_NAME: String = "oauth-token-endpoint"

        /**
         * 既定の回復性の設定: 1 回の試行 5 秒、取得全体の締め切り 10 秒。Retry(`RetryPolicy.DEFAULT`)・リトライバジェット・
         * Circuit Breaker は `shared/resilience` の既定値(ADR-0021 §4・§8)。
         */
        public val DEFAULT_RESILIENCE: ResilienceConfig = ResilienceConfig(attemptTimeout = 5.seconds, deadline = 10.seconds)

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

/** 締め切りで打ち切った取得の理由(`TokenEndpointUnavailable.reason`)。 */
private const val DEADLINE_EXCEEDED = "deadline_exceeded"

/**
 * `Resilience` のエラーを [TokenError] に写す(`token()` の戻り値の型を変えないため)。`circuit_open` は Open が明けるまでの時間を持つ。
 */
private fun DomainError.toTokenError(): TokenError =
    when (this) {
        is TokenError -> this

        is AttemptTimedOut -> TokenEndpointUnavailable("timeout")

        is DeadlineExceeded -> TokenEndpointUnavailable(DEADLINE_EXCEEDED)

        is CircuitOpen -> TokenEndpointUnavailable("circuit_open", retryAfter = retryAfter)

        is BulkheadFull -> TokenEndpointUnavailable("bulkhead_full")

        // 試行は TokenError だけを、Resilience は上の ResilienceError だけを返す
        else -> error("想定外のエラーです: $code")
    }

/**
 * ロックを取れたかを返す。[limit](呼び出し元の締め切りの残り時間)があれば、待つのはその間だけにする。
 * Bulkhead と同じく、取れたかを `withTimeoutOrNull` の戻り値では判断しない(ADR-0021 §5)。`lock()` が戻った直後に期限が来ると
 * `withTimeoutOrNull` は `null` を返すが、ロックは取れているため。`lock()` の直後の代入は中断しないので、`acquired` は取得の成否を表す。
 */
private suspend fun Mutex.lockWithin(limit: Duration?): Boolean {
    if (limit == null) {
        lock()
        return true
    }
    var acquired = tryLock()
    if (!acquired && limit.isPositive()) {
        withTimeoutOrNull(limit) {
            lock()
            acquired = true
        }
    }
    return acquired
}

/** 設定の締め切り [own] より短い予算(呼び出し元の締め切りの残り時間。ADR-0021 §12)で打ち切られた結果か。 */
private fun DomainError.isCallerDeadline(own: Duration?): Boolean = this is DeadlineExceeded && (own == null || deadline < own)

/** 接続の失敗を [TokenEndpointUnavailable] にする。例外のメッセージは URL やヘッダを含みうるため、型の名前だけをログに残す。 */
private fun unreachable(
    e: Exception,
    classifier: HttpCallClassifier,
): TokenEndpointUnavailable {
    unreachableLogger.debug("トークンエンドポイントに接続できません exception={}", e::class.qualifiedName)
    return TokenEndpointUnavailable(classifier.classify(e).reason)
}

// ログの出力元は ClientCredentialsTokenProvider のまま(運用で見るロガー名を変えない)
private val unreachableLogger = LoggerFactory.getLogger(ClientCredentialsTokenProvider::class.java)
