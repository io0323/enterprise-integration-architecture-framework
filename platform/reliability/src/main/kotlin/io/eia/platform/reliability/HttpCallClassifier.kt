package io.eia.platform.reliability

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Clock

/**
 * Ktor Client の呼び出しの結果を、Retryable / NonRetryable の [DomainError] に分類する(INTEGRATION_STANDARDS §3。ADR-0021 §3)。
 * `shared/resilience` の `Resilience.execute` の中で使い、分類の結果で Circuit Breaker とリトライが動く。
 *
 * ```kotlin
 * resilience.execute {
 *     classifier.call(send = { client.get(url) { expectSuccess = false } }) { response -> ok(response.body<Order>()) }
 * }
 * ```
 *
 * | 結果 | 分類 |
 * |---|---|
 * | 2xx | [call] の `read` の結果 |
 * | [retryableStatuses](既定 408・429・502・503・504) | [HttpCallUnavailable](Retryable)。429 と 503 は `Retry-After` を retryAfter に入れる |
 * | それ以外の 4xx・5xx(500 を含む) | [HttpCallRejected](NonRetryable) |
 * | 接続の失敗(`IOException`・名前解決の失敗) | [HttpCallUnavailable](Retryable。reason は `connection`) |
 * | Ktor の HttpTimeout・接続と読み取りのタイムアウト | [HttpCallUnavailable](Retryable。reason は `timeout`) |
 * | キャンセル・そのほかの例外 | 捕まえずに伝える(プログラムの誤りを依存先の障害として数えないため。ADR-0021 §3) |
 *
 * Ktor 3 の `HttpRequestTimeoutException` はキャンセル(`CancellationException`)ではない(実際の接続のテストで確認)。
 *
 * 500 を一時的な障害として扱いたい依存先は、[retryableStatuses] に 500 を加える(依存先ごとの判断。ADR-0021 Consequences)。
 *
 * @param retryableStatuses Retryable にするステータス
 * @param clock `Retry-After` の HTTP-date を待ち時間にするときの現在時刻
 */
public class HttpCallClassifier(
    public val retryableStatuses: Set<Int> = DEFAULT_RETRYABLE_STATUSES,
    private val clock: Clock = Clock.System,
) {
    init {
        require(retryableStatuses.none { it in SUCCESS_STATUSES }) { "2xx は Retryable にできません: $retryableStatuses" }
    }

    /**
     * [send] で要求し、2xx なら [read] で本文を読む。本文の読み取りも 1 回の試行に含める(試行の Timeout が効くように)。
     * [send] は `expectSuccess = false` で呼ぶ。`expectSuccess = true` の `ResponseException` も、その応答で分類する。
     * 例外はメッセージを使わない(URL やヘッダを含みうるため)。
     */
    public suspend fun <T> call(
        send: suspend () -> HttpResponse,
        read: suspend (HttpResponse) -> Result<T, DomainError>,
    ): Result<T, DomainError> =
        try {
            val response = send()
            if (response.status.isSuccess()) read(response) else err(classify(response).asDomainError())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            err(classify(e.response).asDomainError())
        } catch (e: IOException) {
            err(classify(e))
        } catch (e: UnresolvedAddressException) {
            err(classify(e))
        }

    /** 2xx 以外の応答を分類する。 */
    public fun classify(response: HttpResponse): HttpCallError = classify(response.status, response.headers[HttpHeaders.RetryAfter])

    /** 2xx 以外のステータスを分類する。[retryAfter] は `Retry-After` の値(429 と 503 のときだけ使う)。 */
    public fun classify(
        status: HttpStatusCode,
        retryAfter: String?,
    ): HttpCallError {
        val code = status.value
        require(code !in SUCCESS_STATUSES) { "2xx は失敗ではありません: $code" }
        return when (code) {
            in retryableStatuses -> {
                val wait = if (code in RETRY_AFTER_STATUSES) RetryAfter.parse(retryAfter, clock.now()) else null
                HttpCallUnavailable(code, REASON_STATUS, wait)
            }

            else -> {
                HttpCallRejected(code)
            }
        }
    }

    /** 接続の失敗・タイムアウトの例外を分類する。 */
    public fun classify(exception: Exception): HttpCallUnavailable =
        when (exception) {
            is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException -> {
                HttpCallUnavailable(null, REASON_TIMEOUT)
            }

            else -> {
                HttpCallUnavailable(null, REASON_CONNECTION)
            }
        }

    public companion object {
        /** INTEGRATION_STANDARDS §3 のリトライ対象のステータス。 */
        public val DEFAULT_RETRYABLE_STATUSES: Set<Int> = setOf(408, 429, 502, 503, 504)

        /** `Retry-After` を使うステータス(INTEGRATION_STANDARDS §3: 429/503 は Retry-After を優先)。 */
        public val RETRY_AFTER_STATUSES: Set<Int> = setOf(429, 503)

        public const val REASON_STATUS: String = "status"
        public const val REASON_CONNECTION: String = "connection"
        public const val REASON_TIMEOUT: String = "timeout"

        private val SUCCESS_STATUSES = 200..299
    }
}
