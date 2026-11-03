package io.eia.platform.grpc

import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockRequest
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockResponse
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpcKt
import io.eia.platform.observability.propagation.EiaTraceContextPropagator
import io.eia.platform.security.jwt.JwtRejectionReason
import io.eia.platform.security.jwt.JwtVerificationError
import io.eia.platform.security.jwt.VerifiedToken
import io.eia.platform.security.tls.ServerTls
import io.eia.platform.security.tls.TlsFiles
import io.eia.platform.security.tls.TlsVariables
import io.eia.platform.testsupport.TestPki
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.ok
import io.eia.shared.sdk.http.token.AccessToken
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

internal const val GOOD_TOKEN = "good-token"
internal const val SCOPE = "inventory.stock:read"

/** テスト用の OTel(メモリに記録する)。 */
internal class TestTelemetry {
    val spans: InMemorySpanExporter = InMemorySpanExporter.create()
    val metrics: InMemoryMetricReader = InMemoryMetricReader.create()
    private val sdk =
        OpenTelemetrySdk
            .builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .setPropagators(ContextPropagators.create(EiaTraceContextPropagator))
            .build()
    val telemetry = GrpcTelemetry(sdk, sdk.getTracer("test"), sdk.getMeter("test"))

    /** [name] のカウンタの、属性 [key] の値ごとの合計。 */
    fun counter(
        name: String,
        key: String,
    ): Map<String, Long> =
        metrics
            .collectAllMetrics()
            .filter { it.name == name }
            .flatMap { it.longSumData.points }
            .associate { point ->
                point.attributes
                    .asMap()
                    .entries
                    .first { it.key.key == key }
                    .value
                    .toString() to point.value
            }

    /** [name] のヒストグラムの点の、属性 [key] の値の一覧。 */
    fun histogramValues(
        name: String,
        key: String,
    ): List<String> =
        metrics
            .collectAllMetrics()
            .filter { it.name == name }
            .flatMap { it.histogramData.points }
            .flatMap { point ->
                point.attributes
                    .asMap()
                    .entries
                    .filter { it.key.key == key }
                    .map { it.value.toString() }
            }
}

internal fun TestPki.Issued.tls(ca: TestPki): ServerTls =
    checkNotNull(
        ServerTls
            .load(TlsFiles(TlsVariables("TEST"), certFile.toString(), keyFile.toString(), ca.caFile.toString()))
            .getOrNull(),
    ) { "テストの証明書を読めません" }

internal fun verifiedToken(scopes: Set<String>) =
    VerifiedToken("iss", "sub", "client", listOf("aud"), scopes, Clock.System.now(), Clock.System.now() + 1.hours, null)

/** [GOOD_TOKEN] だけを受け付ける。[scopes] がトークンのスコープ。 */
internal fun verifier(scopes: Set<String> = setOf(SCOPE)): suspend (String) -> Result<VerifiedToken, JwtVerificationError> =
    { token ->
        if (token == GOOD_TOKEN) {
            ok(verifiedToken(scopes))
        } else {
            err(JwtVerificationError.InvalidToken(JwtRejectionReason.BAD_SIGNATURE))
        }
    }

/** 順に返すトークン(最後のものを使い続ける)。捨てられたトークンを記録する。 */
internal class TestTokens(
    vararg values: String,
) : GrpcTokenSource {
    private val remaining = ArrayDeque(values.toList())
    val invalidated = mutableListOf<String>()
    var issued = 0

    override suspend fun token(): Result<AccessToken, DomainError> {
        issued++
        val value = if (remaining.size > 1) remaining.removeFirst() else remaining.first()
        return ok(AccessToken(value, Clock.System.now() + 1.hours, emptySet()))
    }

    override suspend fun invalidate(token: AccessToken) {
        invalidated += token.reveal()
    }
}

/** [handler] で応答する在庫の照会。受け取った要求と metadata はテストの本体で確かめる。 */
internal class TestStockService(
    private val handler: suspend (BatchGetStockRequest) -> BatchGetStockResponse = { BatchGetStockResponse.getDefaultInstance() },
) : StockQueryServiceGrpcKt.StockQueryServiceCoroutineImplBase() {
    override suspend fun batchGetStock(request: BatchGetStockRequest): BatchGetStockResponse = handler(request)
}
