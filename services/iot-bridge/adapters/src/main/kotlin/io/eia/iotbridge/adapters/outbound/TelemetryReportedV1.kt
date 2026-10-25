package io.eia.iotbridge.adapters.outbound

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// `iot.telemetry.reported.v1` のイベントの型(contracts/avro/iot/TelemetryReported.avsc。INT-IOT-002)。
// avro4k は型とスキーマを record のフルネームで対応させるため、@SerialName を契約の record のフルネームにする(ADR-0025 §1)。

@Serializable
@SerialName("io.eia.events.iot.TelemetryReported")
internal data class TelemetryReportedV1(
    val tenant: String,
    val deviceId: String,
    val bootId: String,
    val seq: Long,
    val payloadVersion: Int,
    val deviceTime: Instant? = null,
    val receivedAt: Instant,
    val clockSuspect: Boolean,
    val readings: List<ReadingV1>,
    val batteryPercent: Int? = null,
)

@Serializable
@SerialName("io.eia.events.iot.Reading")
internal data class ReadingV1(
    val name: String,
    val value: Double,
    val unit: String? = null,
)
