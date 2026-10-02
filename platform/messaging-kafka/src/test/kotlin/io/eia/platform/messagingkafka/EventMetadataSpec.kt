package io.eia.platform.messagingkafka

import io.eia.shared.kernel.CorrelationId
import io.eia.shared.resilience.trace.TraceParent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.apache.kafka.common.header.internals.RecordHeader
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val METADATA =
    EventMetadata(
        id = Uuid.parse("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"),
        source = "/sales/order-service",
        type = "sales.order.created",
        time = Instant.parse("2026-10-02T01:02:03.456789Z"),
        traceParent = TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").ok(),
        correlationId = CorrelationId.parse("c-123").ok(),
    )

class EventMetadataSpec :
    FunSpec({
        test("CloudEvents binary mode のヘッダ(AsyncAPI の StandardHeaders)を UTF-8 の文字列で書く") {
            METADATA.toHeaders().associate { it.key() to String(it.value(), Charsets.UTF_8) } shouldBe
                mapOf(
                    "ce_id" to "0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b",
                    "ce_source" to "/sales/order-service",
                    "ce_type" to "sales.order.created",
                    "ce_time" to "2026-10-02T01:02:03.456789Z",
                    "ce_specversion" to "1.0",
                    "traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                    "correlationid" to "c-123",
                )
        }

        test("ヘッダから読み戻せる(ほかのヘッダは無視する)") {
            EventMetadata.fromHeaders(METADATA.toHeaders() + RecordHeader("other", byteArrayOf(1))).ok() shouldBe METADATA
        }

        test("Debezium が Outbox の timestamptz を書いた形式(オフセット付き)の ce_time も読める") {
            val headers =
                METADATA.toHeaders().filter { it.key() != "ce_time" } +
                    RecordHeader("ce_time", "2026-10-02T10:02:03.456789+09:00".toByteArray())
            EventMetadata.fromHeaders(headers).ok().time shouldBe METADATA.time
        }

        test("必須のヘッダの欠落と形式の不正を、項目ごとにまとめて返す(値はメッセージに入れない)") {
            val headers =
                METADATA.toHeaders().filter { it.key() !in setOf("ce_id", "correlationid") } +
                    RecordHeader("ce_specversion", "0.3".toByteArray()) +
                    RecordHeader("traceparent", "broken".toByteArray())

            EventMetadata
                .fromHeaders(headers)
                .err()
                .violations
                .map { it.field to it.reason } shouldContainExactlyInAnyOrder
                listOf(
                    "ce_id" to "ありません",
                    "correlationid" to "ありません",
                    "ce_specversion" to "形式が不正です",
                    "traceparent" to "形式が不正です",
                )
        }
    })
