package io.eia.platform.grpc

import io.eia.platform.observability.ObservabilityRuntime
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** gRPC の部品が使う OTel の API(SDK の型は公開しない。ADR-0004 §4)。 */
public class GrpcTelemetry(
    public val openTelemetry: OpenTelemetry,
    public val tracer: Tracer,
    public val meter: Meter,
) {
    public companion object {
        public fun of(runtime: ObservabilityRuntime): GrpcTelemetry = GrpcTelemetry(runtime.openTelemetry, runtime.tracer, runtime.meter)
    }
}

/**
 * gRPC のメトリクス(Framework 14.1。ADR-0042 §9)。
 *
 * | メトリクス | 種類 | 属性 |
 * |---|---|---|
 * | `rpc.client.call.duration` | histogram(秒) | 共通の属性 + `eia.dependency.name` |
 * | `rpc.server.call.duration` | histogram(秒) | 共通の属性 |
 * | `eia.grpc.client.certificate_failures` | counter | `eia.dependency.name`・`reason`([CertificateFailureReason]) |
 * | `eia.grpc.server.certificate_rejections` | counter | `reason`(`expired` / `untrusted` / `client_not_allowed`) |
 *
 * 共通の属性は `rpc.system.name`・`rpc.method`・`rpc.response.status_code`・(失敗のとき)`error.type`。
 * 属性は決まった値だけにする(カーディナリティ対策)。要求の内容・トークン・証明書の中身は入れない。
 */
internal class GrpcMetrics(
    meter: Meter,
) {
    private val client: DoubleHistogram = meter.durationHistogram("rpc.client.call.duration", "送信した gRPC の呼び出しの時間")
    private val server: DoubleHistogram = meter.durationHistogram("rpc.server.call.duration", "受信した gRPC の呼び出しの処理時間")
    private val clientCertificates: LongCounter =
        meter
            .counterBuilder("eia.grpc.client.certificate_failures")
            .setUnit("{failure}")
            .setDescription("証明書が原因で gRPC の接続に失敗した件数(再試行しても直らない。Circuit Breaker には数えない)")
            .build()
    private val serverCertificates: LongCounter =
        meter
            .counterBuilder("eia.grpc.server.certificate_rejections")
            .setUnit("{rejection}")
            .setDescription("クライアント証明書を拒否した件数")
            .build()

    fun recordClient(
        duration: Duration,
        method: String,
        dependency: String,
        statusCode: String?,
        errorType: String?,
    ) {
        val attributes = call(method, statusCode, errorType).put(DEPENDENCY, dependency).build()
        client.record(duration.toDouble(DurationUnit.SECONDS), attributes)
    }

    fun recordServer(
        duration: Duration,
        method: String,
        statusCode: String?,
        errorType: String?,
    ) {
        server.record(duration.toDouble(DurationUnit.SECONDS), call(method, statusCode, errorType).build())
    }

    fun recordCertificateFailure(
        dependency: String,
        reason: CertificateFailureReason,
    ) {
        clientCertificates.add(1, Attributes.of(DEPENDENCY, dependency, REASON, reason.label))
    }

    fun recordCertificateRejection(reason: String) {
        serverCertificates.add(1, Attributes.of(REASON, reason))
    }

    private fun call(
        method: String,
        statusCode: String?,
        errorType: String?,
    ): AttributesBuilder =
        Attributes
            .builder()
            .put(RPC_SYSTEM, "grpc")
            .put(RPC_METHOD, method)
            .apply {
                statusCode?.let { put(RPC_STATUS, it) }
                errorType?.let { put(ERROR_TYPE, it) }
            }

    companion object {
        val RPC_SYSTEM: AttributeKey<String> = AttributeKey.stringKey("rpc.system.name")
        val RPC_METHOD: AttributeKey<String> = AttributeKey.stringKey("rpc.method")
        val RPC_STATUS: AttributeKey<String> = AttributeKey.stringKey("rpc.response.status_code")
        val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")
        val DEPENDENCY: AttributeKey<String> = AttributeKey.stringKey("eia.dependency.name")
        val REASON: AttributeKey<String> = AttributeKey.stringKey("reason")

        /** semconv の既定のバケット境界(秒)。HttpMetrics と同じ。 */
        private val BUCKETS = listOf(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0)

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
