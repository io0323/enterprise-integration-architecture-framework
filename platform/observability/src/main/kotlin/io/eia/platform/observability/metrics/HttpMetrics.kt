package io.eia.platform.observability.metrics

import io.eia.platform.observability.context.LogKeys
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.semconv.ErrorAttributes
import io.opentelemetry.semconv.HttpAttributes
import io.opentelemetry.semconv.ServerAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** 1 回の HTTP のやり取りの結果(メトリクスの属性)。[status] は応答がないとき `null`、[errorType] はエラーでないとき `null`。 */
public data class HttpExchange(
    public val method: String,
    public val status: Int?,
    public val errorType: String?,
)

/**
 * HTTP の RED メトリクス(Framework 14.1)。OTel semconv の `http.server.request.duration` / `http.client.request.duration`
 * (単位は秒のヒストグラム)で、件数(Rate)・`error.type` 付きの件数(Error)・分布(Duration)をまとめて表す。
 *
 * 属性はカーディナリティを抑えるため、route はテンプレート(`/v1/orders/{id}`)だけを使い、生のパスや ID は入れない。
 */
public class HttpMetrics(
    meter: Meter,
) {
    private val server: DoubleHistogram = meter.durationHistogram("http.server.request.duration", "受信した HTTP リクエストの処理時間")
    private val client: DoubleHistogram = meter.durationHistogram("http.client.request.duration", "送信した HTTP リクエストの時間")
    private val invalidCorrelationIds: LongCounter =
        meter
            .counterBuilder("eia.http.server.correlation_id.invalid")
            .setUnit("{request}")
            .setDescription("不正な X-Correlation-Id を受信し、採番し直した件数")
            .build()

    private val deadlineOverruns: LongCounter =
        meter
            .counterBuilder("eia.http.server.deadline_overruns")
            .setUnit("{request}")
            .setDescription("応答を返し始めた後にリクエストの予算を超えた件数(クライアントは応答を受け取っているので、エラーには数えない)")
            .build()

    /**
     * 応答を返し始めた後に、リクエストの予算を超えた件数を数える(ADR-0024 §3)。RED の Errors(`error.type`)はクライアントが
     * 受け取った結果に合わせるため、この件数はエラーとは別に数える。属性は [recordServer] と同じ(状態コードとメソッド・ルート)。
     */
    public fun recordDeadlineOverrun(
        exchange: HttpExchange,
        route: String?,
        integrationId: String?,
    ) {
        val attributes =
            exchange
                .attributes()
                .apply {
                    route?.let { put(HttpAttributes.HTTP_ROUTE, it) }
                    integrationId?.let { put(INTEGRATION_ID, it) }
                }.build()
        deadlineOverruns.add(1, attributes)
    }

    /** 不正な `X-Correlation-Id` を受信した(採番し直した)件数を数える。 */
    public fun recordInvalidCorrelationId(integrationId: String?) {
        invalidCorrelationIds.add(1, integrationId?.let { Attributes.of(INTEGRATION_ID, it) } ?: Attributes.empty())
    }

    public fun recordServer(
        duration: Duration,
        exchange: HttpExchange,
        route: String?,
        integrationId: String?,
    ) {
        val attributes =
            exchange
                .attributes()
                .apply {
                    route?.let { put(HttpAttributes.HTTP_ROUTE, it) }
                    integrationId?.let { put(INTEGRATION_ID, it) }
                }.build()
        server.record(duration.toDouble(DurationUnit.SECONDS), attributes)
    }

    public fun recordClient(
        duration: Duration,
        exchange: HttpExchange,
        serverAddress: String,
    ) {
        val attributes = exchange.attributes().put(ServerAttributes.SERVER_ADDRESS, serverAddress).build()
        client.record(duration.toDouble(DurationUnit.SECONDS), attributes)
    }

    private fun HttpExchange.attributes(): AttributesBuilder =
        Attributes
            .builder()
            .put(HttpAttributes.HTTP_REQUEST_METHOD, normalizeMethod(method))
            .apply {
                status?.let { put(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, it.toLong()) }
                errorType?.let { put(ErrorAttributes.ERROR_TYPE, it) }
            }

    public companion object {
        public val INTEGRATION_ID: AttributeKey<String> = AttributeKey.stringKey(LogKeys.INTEGRATION_ID)

        /** semconv の既定のバケット境界(秒)。 */
        private val BUCKETS = listOf(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0)
        private val KNOWN_METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH")
        private const val OTHER_METHOD = "_OTHER"

        /** semconv: 既知でないメソッドは `_OTHER` にまとめる(任意の文字列でカーディナリティが増えないように)。 */
        public fun normalizeMethod(method: String): String = method.uppercase().takeIf { it in KNOWN_METHODS } ?: OTHER_METHOD

        /** タイムアウト(`withTimeout` の期限切れ)の `error.type`。semconv は低カーディナリティの独自の値を許す。 */
        public const val TIMEOUT: String = "timeout"

        /** 例外の `error.type`(semconv: 例外の完全修飾クラス名)。 */
        public fun errorTypeOf(exception: Throwable): String = exception::class.qualifiedName ?: exception::class.java.name

        /**
         * 失敗の `error.type`(ADR-0018 §5)。タイムアウト(`withTimeout`、Ktor の HttpTimeout、接続・読み取りのタイムアウト)は
         * [TIMEOUT] でエラーに数え、それ以外のキャンセル
         * (クライアントの切断・呼び出し側の中止)は、応答していないのでエラーに数えない(`null`)。
         */
        public fun failureErrorType(exception: Throwable): String? =
            when (exception) {
                is TimeoutCancellationException,
                is HttpRequestTimeoutException,
                is ConnectTimeoutException,
                is SocketTimeoutException,
                -> TIMEOUT

                is CancellationException -> null

                else -> errorTypeOf(exception)
            }

        /** 5xx と例外をエラーとする(semconv: サーバの 4xx は呼び出し側の誤りなのでエラーにしない)。 */
        public fun serverErrorType(
            status: Int?,
            exception: Throwable?,
        ): String? = exception?.let(::failureErrorType) ?: status?.takeIf { it >= SERVER_ERROR }?.toString()

        /** クライアントは 4xx もエラーとする(semconv)。 */
        public fun clientErrorType(
            status: Int?,
            exception: Throwable?,
        ): String? = exception?.let(::failureErrorType) ?: status?.takeIf { it >= CLIENT_ERROR }?.toString()

        private const val SERVER_ERROR = 500
        private const val CLIENT_ERROR = 400

        private fun Meter.durationHistogram(
            name: String,
            description: String,
        ): DoubleHistogram =
            histogramBuilder(name)
                .setUnit("s")
                .setDescription(description)
                .setExplicitBucketBoundariesAdvice(BUCKETS)
                .build()
    }
}
