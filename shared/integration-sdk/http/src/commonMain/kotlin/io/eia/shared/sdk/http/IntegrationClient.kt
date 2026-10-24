package io.eia.shared.sdk.http

import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.IdempotencyKey
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.trace.TraceParent
import io.eia.shared.sdk.http.token.AccessToken
import io.eia.shared.sdk.http.token.ClientCredentialsTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import kotlin.random.Random

/**
 * 呼び出しの文脈(Framework 14)。呼び出し元の traceparent と Correlation ID を引き継ぐ。
 * どちらもなければ、[IntegrationClient] が新しいトレースを始め、Correlation ID を採番する(入口としてふるまう)。
 */
public data class CallContext(
    public val traceParent: TraceParent? = null,
    public val correlationId: CorrelationId? = null,
)

/**
 * 社内の API を呼ぶクライアント(ADR-0037 §4)。Gateway の公開 URL(`/{domain}`。ADR-0005)を [baseUrl] にして、1 つの依存先に 1 つを作って使い回す。
 *
 * - 1 回の呼び出しを [resilience] で包む(Timeout・Retry・Circuit Breaker。ADR-0021)。結果は [HttpCallClassifier] で
 *   Retryable / NonRetryable に分け、429 / 503 の `Retry-After` を待ち時間に使う(kernel の RetryPolicy が優先する)。
 * - 要求に `traceparent`(呼び出し元のものの子。なければ新しく始める)と `X-Correlation-Id` を付ける。リトライでも同じ値を送る。
 * - POST は `Idempotency-Key` を必須の引数にし、リトライでも同じキーを送る(Framework 5.4)。
 * - [tokens] があれば `Authorization: Bearer` を付ける。401 なら、そのトークンを捨てて 1 回だけ取り直して送り直す
 *   (IdP の側で失効させられた場合。2 回目の 401 はそのまま NonRetryable)。トークンを取得できなければ、その失敗を返す。
 * - 結果は [Result] で返す。[read] は 2xx の応答を読む(本文の読み取りも 1 回の試行に含める)。
 *
 * **[resilience] は依存先ごとに 1 つを作って使い回す。**(Circuit Breaker とリトライバジェットの状態を持つため)
 *
 * @param httpClient 既定は [defaultEngine] のクライアント。リクエストやヘッダをログに出すプラグイン(Ktor の Logging など)は付けない
 *   (Authorization が漏れるため)
 * @param random traceparent の生成に使う乱数(テストでは固定の種を渡す)
 */
@Suppress("LongParameterList") // 依存(既定値つき。名前付き引数で指定する)
public class IntegrationClient(
    private val baseUrl: Url,
    private val resilience: Resilience,
    private val tokens: ClientCredentialsTokenProvider? = null,
    private val httpClient: HttpClient = HttpClient(defaultEngine()),
    private val classifier: HttpCallClassifier = HttpCallClassifier(),
    private val random: Random = Random.Default,
) {
    /** GET。冪等なのでリトライする。 */
    public suspend fun <T> get(
        path: String,
        context: CallContext = CallContext(),
        read: suspend (HttpResponse) -> Result<T, DomainError>,
    ): Result<T, DomainError> = call(HttpMethod.Get, path, context, body = null, idempotencyKey = null, read = read)

    /** POST。[idempotencyKey] を必須にし、リトライでも同じキーを送る(受け手が重複を捨てる。Framework 5.4・13.1)。 */
    @Suppress("LongParameterList") // 要求の項目(名前付き引数で指定する)
    public suspend fun <T> post(
        path: String,
        body: String,
        contentType: ContentType,
        idempotencyKey: IdempotencyKey,
        context: CallContext = CallContext(),
        read: suspend (HttpResponse) -> Result<T, DomainError>,
    ): Result<T, DomainError> = call(HttpMethod.Post, path, context, Body(body, contentType), idempotencyKey, read)

    @Suppress("LongParameterList") // 要求の項目
    private suspend fun <T> call(
        method: HttpMethod,
        path: String,
        context: CallContext,
        body: Body?,
        idempotencyKey: IdempotencyKey?,
        read: suspend (HttpResponse) -> Result<T, DomainError>,
    ): Result<T, DomainError> {
        // リトライでも同じ traceparent・Correlation ID を送る(1 回の呼び出しを 1 つのスパンとして扱う)
        val traceParent = context.traceParent?.child(random) ?: TraceParent.generate(random)
        val correlationId = context.correlationId ?: CorrelationId.generate(random)
        val request: HttpRequestBuilder.() -> Unit = {
            this.method = method
            url {
                takeFrom(baseUrl)
                appendPathSegments(path.trimStart('/').split('/'))
            }
            expectSuccess = false
            header(TRACEPARENT, traceParent.format())
            header(CORRELATION_ID, correlationId.value)
            idempotencyKey?.let { header(IDEMPOTENCY_KEY, it.value) }
            body?.let {
                contentType(it.contentType)
                setBody(it.text)
            }
        }
        return resilience.execute { attempt(request, read) }
    }

    /** 1 回の試行。401 なら、トークンを捨てて 1 回だけ取り直して送り直す(2 回目の 401 はそのまま返す)。 */
    private suspend fun <T> attempt(
        request: HttpRequestBuilder.() -> Unit,
        read: suspend (HttpResponse) -> Result<T, DomainError>,
    ): Result<T, DomainError> =
        token().flatMap { first ->
            val sent = send(request, read, first)
            if (!sent.unauthorized || first == null) {
                sent.result
            } else {
                tokens?.invalidate(first)
                token().flatMap { second -> send(request, read, second).result }
            }
        }

    /** [token] を付けて送り、分類した結果と 401 だったかを返す。 */
    private suspend fun <T> send(
        request: HttpRequestBuilder.() -> Unit,
        read: suspend (HttpResponse) -> Result<T, DomainError>,
        token: AccessToken?,
    ): Sent<T> {
        var unauthorized = false
        val result =
            classifier.call(
                send = {
                    httpClient
                        .request {
                            request()
                            token?.let { header(HttpHeaders.Authorization, it.authorizationHeader()) }
                        }.also { unauthorized = it.status == HttpStatusCode.Unauthorized }
                },
                read = read,
            )
        return Sent(result, unauthorized)
    }

    private class Sent<T>(
        val result: Result<T, DomainError>,
        val unauthorized: Boolean,
    )

    /** [tokens] があればトークンを、なければ null を返す。 */
    private suspend fun token(): Result<AccessToken?, DomainError> =
        when (val provider = tokens) {
            null -> {
                Result.Ok(null)
            }

            else -> {
                when (val result = provider.token()) {
                    is Result.Ok -> Result.Ok(result.value)
                    is Result.Err -> err(result.error.asDomainError())
                }
            }
        }

    private class Body(
        val text: String,
        val contentType: ContentType,
    )

    private companion object {
        // INTEGRATION_STANDARDS §2 の標準ヘッダ
        const val TRACEPARENT = "traceparent"
        const val CORRELATION_ID = "X-Correlation-Id"
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
