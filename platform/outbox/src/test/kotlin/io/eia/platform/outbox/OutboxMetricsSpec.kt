package io.eia.platform.outbox

import io.eia.platform.messagingkafka.EventTopic
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlin.uuid.Uuid

class OutboxMetricsSpec :
    FunSpec({
        test("追記の件数をトピックごとに、失敗の件数を error.code ごとに数える") {
            val reader = InMemoryMetricReader.create()
            val metrics =
                OutboxMetrics(
                    SdkMeterProvider
                        .builder()
                        .registerMetricReader(reader)
                        .build()
                        .get("test"),
                )
            val other = EventTopic.of("test.parcel.lost.v1")

            metrics.appended(
                listOf(
                    record(),
                    record(Uuid.parse("0199b6a0-0000-7000-8000-000000000002")),
                    record(Uuid.parse("0199b6a0-0000-7000-8000-000000000003"), other),
                ),
            )
            metrics.failed(OutboxMisuse("x"))

            val collected = reader.collectAllMetrics().associateBy { it.name }
            collected.getValue("eia.outbox.appended").longSumData.points.associate {
                it.attributes.get(AttributeKey.stringKey("messaging.destination.name")) to it.value
            } shouldBe mapOf("test.parcel.shipped.v1" to 2L, "test.parcel.lost.v1" to 1L)
            collected.getValue("eia.outbox.append.failures").longSumData.points.single().let {
                it.attributes.get(AttributeKey.stringKey("error.code")) shouldBe "outbox_misuse"
                it.value shouldBe 1L
            }
        }
    })
