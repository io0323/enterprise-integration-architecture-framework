package io.eia.platform.audit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.time.Duration

class AuditMetricsSpec :
    FunSpec({
        test("追記の所要時間・ロックの待ち時間(秒)と、失敗の件数(error.code 別)を記録する") {
            val reader = InMemoryMetricReader.create()
            SdkMeterProvider.builder().registerMetricReader(reader).build().use { provider ->
                val metrics = AuditMetrics(provider.get("audit-test"))
                metrics.appended(Duration.ofMillis(12), Duration.ofMillis(3))
                metrics.failed(AuditStorageUnavailable("db", "SQLException"))
                metrics.failed(AuditStorageUnavailable("db", "SQLException"))

                val collected = reader.collectAllMetrics().associateBy { it.name }
                val duration =
                    collected
                        .getValue("eia.audit.append.duration")
                        .histogramData.points
                        .single()
                duration.count shouldBe 1L
                duration.sum shouldBe 0.012
                collected
                    .getValue("eia.audit.lock.wait")
                    .histogramData.points
                    .single()
                    .sum shouldBeGreaterThan 0.0
                val failure =
                    collected
                        .getValue("eia.audit.append.failures")
                        .longSumData.points
                        .single()
                failure.value shouldBe 2L
                failure.attributes.get(AttributeKey.stringKey("error.code")) shouldBe "audit_storage_unavailable"
            }
        }
    })
