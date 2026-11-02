package io.eia.b2bgateway.app

import io.eia.b2bgateway.application.port.inbound.AcceptedDocument
import io.eia.b2bgateway.application.port.inbound.DocumentOutcome
import io.eia.b2bgateway.application.port.inbound.PollSummary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader

class B2bGatewayMetricsSpec :
    FunSpec({
        test("拒否のカウンタは、起動時に取引先と段階ごとに 0 で作る(Prometheus の increase が最初の拒否を数えられるように)。拒否した文書で増える") {
            val reader = InMemoryMetricReader.create()
            val meter =
                SdkMeterProvider
                    .builder()
                    .registerMetricReader(reader)
                    .build()
                    .get("test")
            val metrics = B2bGatewayMetrics(meter, EdiPartners(listOf("partner01"), listOf("partner01")))

            fun rejected(): Map<String, Long> =
                reader
                    .collectAllMetrics()
                    .single { it.name == "eia.b2b.documents.rejected" }
                    .longSumData
                    .points
                    .associate { it.attributes.get(AttributeKey.stringKey("b2b.stage"))!! to it.value }

            rejected() shouldBe mapOf("decryption" to 0L, "syntax" to 0L, "schema" to 0L, "business" to 0L)

            metrics.polled(
                "partner01",
                PollSummary(
                    listOf(AcceptedDocument("d1", DocumentOutcome.UNREADABLE, null), AcceptedDocument("d2", DocumentOutcome.RESENT, null)),
                    emptyList(),
                ),
            )

            rejected() shouldBe mapOf("decryption" to 1L, "syntax" to 0L, "schema" to 0L, "business" to 0L)
        }
    })
