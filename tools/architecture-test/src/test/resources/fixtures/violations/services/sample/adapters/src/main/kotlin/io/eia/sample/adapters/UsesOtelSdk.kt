package io.eia.sample.adapters

// 違反: adapters の本番コードで OTel SDK を使う(ADR-0004 §4。SDK は platform/observability と app だけ)
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.trace.SdkTracerProvider

class UsesOtelSdk(
    val tracer: Tracer,
) {
    fun provider(): SdkTracerProvider = SdkTracerProvider.builder().build()
}
