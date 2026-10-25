package io.eia.iotbridge.adapters.outbound

import io.eia.iotbridge.domain.DeviceAddress
import io.eia.iotbridge.domain.Reading
import io.eia.iotbridge.domain.Telemetry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import kotlin.time.Instant

class IotEventSchemasSpec :
    FunSpec({
        test("トピックは iot.telemetry.reported.v1、スキーマは契約のファイルの内容") {
            IotEventSchemas.TELEMETRY_REPORTED.name shouldBe "iot.telemetry.reported.v1"
            IotEventSchemas.telemetryReported.schema shouldBe
                java.io.File(checkNotNull(System.getProperty("eia.contractsAvro")), "iot/TelemetryReported.avsc").readText()
        }

        @OptIn(ExperimentalSerializationApi::class)
        test("イベントの型は契約のスキーマで書け、読み戻すと値が一致する(デバイスの時刻・単位・電池は省略できる)") {
            val telemetry =
                Telemetry(
                    address = DeviceAddress("t1", "d-001"),
                    bootId = "0123456789abcdef",
                    seq = 3,
                    payloadVersion = 1,
                    deviceTime = null,
                    receivedAt = Instant.parse("2026-10-25T00:00:00.123456Z"),
                    clockSuspect = true,
                    readings = listOf(Reading("temperature", 21.5, "Cel"), Reading("co2", 410.0, null)),
                    batteryPercent = null,
                )
            val schema = Schema.Parser().parse(IotEventSchemas.telemetryReported.schema)
            val bytes =
                com.github.avrokotlin.avro4k.Avro
                    .encodeToByteArray(schema, TelemetryReportedV1.serializer(), telemetry.toEvent())

            val record = GenericDatumReader<GenericRecord>(schema).read(null, DecoderFactory.get().binaryDecoder(bytes, null))
            record.get("tenant").toString() shouldBe "t1"
            record.get("deviceId").toString() shouldBe "d-001"
            record.get("seq") shouldBe 3L
            record.get("deviceTime") shouldBe null
            record.get("receivedAt") shouldBe 1_792_886_400_123_456L
            record.get("clockSuspect") shouldBe true
            (record.get("readings") as List<*>).size shouldBe 2
            record.get("batteryPercent") shouldBe null
        }
    })
