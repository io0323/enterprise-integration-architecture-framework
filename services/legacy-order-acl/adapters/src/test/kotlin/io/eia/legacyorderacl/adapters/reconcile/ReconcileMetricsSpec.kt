package io.eia.legacyorderacl.adapters.reconcile

import io.eia.legacyorderacl.application.port.inbound.Mismatch
import io.eia.legacyorderacl.application.port.inbound.ReconciliationReport
import io.eia.shared.kernel.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ReconcileMetricsSpec :
    FunSpec({
        test("最後に成功した時刻・間隔・種類ごとのずれ・変換できないキー・結果ごとの回数") {
            val reader = InMemoryMetricReader.create()
            val clock = FixedClock(Instant.parse("2026-10-08T00:00:00Z"))
            val metrics =
                ReconcileMetrics(
                    SdkMeterProvider
                        .builder()
                        .registerMetricReader(reader)
                        .build()
                        .get("test"),
                    2.minutes,
                    clock,
                )

            metrics.succeeded(
                ReconciliationReport(
                    "0/1",
                    4,
                    mapOf("J1" to Mismatch.STALE, "J2" to Mismatch.STALE, "J3" to Mismatch.EXTRA),
                    setOf("J4"),
                    "d",
                ),
            )
            metrics.failed("unavailable")

            val collected = reader.collectAllMetrics().associateBy { it.name }
            collected
                .getValue("eia.reconcile.last_success")
                .doubleGaugeData.points
                .single()
                .value shouldBe 1_791_417_600.0
            collected
                .getValue("eia.reconcile.interval")
                .doubleGaugeData.points
                .single()
                .value shouldBe 120.0
            collected
                .getValue("eia.reconcile.drift_keys")
                .longGaugeData.points
                .associate {
                    it.attributes
                        .asMap()
                        .values
                        .single()
                        .toString() to it.value
                } shouldBe
                mapOf("missing" to 0L, "stale" to 2L, "extra" to 1L)
            collected
                .getValue("eia.reconcile.unconvertible_keys")
                .longGaugeData.points
                .single()
                .value shouldBe 1L
            collected
                .getValue("eia.reconcile.checks")
                .longSumData.points
                .associate { point ->
                    point.attributes
                        .asMap()
                        .values
                        .map { it.toString() }
                        .sorted() to point.value
                } shouldBe
                mapOf(listOf("drift") to 1L, listOf("error", "unavailable") to 1L)
            metrics.lastSucceededAt shouldBe Instant.parse("2026-10-08T00:00:00Z")
        }
    })
