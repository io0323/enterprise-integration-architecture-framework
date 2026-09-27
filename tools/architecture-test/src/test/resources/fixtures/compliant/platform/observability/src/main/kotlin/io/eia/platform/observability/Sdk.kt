package io.eia.platform.observability

// 準拠: platform/observability は OTel SDK を初期化する(ADR-0004 §4)
import io.opentelemetry.sdk.OpenTelemetrySdk

fun sdk(): OpenTelemetrySdk = OpenTelemetrySdk.builder().build()
