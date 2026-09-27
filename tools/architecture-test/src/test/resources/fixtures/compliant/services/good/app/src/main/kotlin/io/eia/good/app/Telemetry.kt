package io.eia.good.app

// 準拠: app は OTel SDK を組み立ててよい(ADR-0004 §4)
import io.opentelemetry.sdk.OpenTelemetrySdk

fun telemetry(): OpenTelemetrySdk = OpenTelemetrySdk.builder().build()
