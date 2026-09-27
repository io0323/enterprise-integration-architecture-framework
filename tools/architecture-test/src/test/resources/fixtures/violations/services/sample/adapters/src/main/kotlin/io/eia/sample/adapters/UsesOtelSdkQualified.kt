package io.eia.sample.adapters

// 違反: import せずに完全修飾名で OTel SDK を使う(ADR-0004 §4)
class UsesOtelSdkQualified {
    fun provider(): Any = io.opentelemetry.sdk.trace.SdkTracerProvider.builder().build()
}
