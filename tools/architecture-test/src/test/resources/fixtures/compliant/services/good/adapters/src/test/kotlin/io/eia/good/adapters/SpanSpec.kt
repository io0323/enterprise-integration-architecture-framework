package io.eia.good.adapters

// 準拠: テストのソースセットは InMemory の exporter のために OTel SDK を使ってよい
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter

val exporter: InMemorySpanExporter = InMemorySpanExporter.create()
