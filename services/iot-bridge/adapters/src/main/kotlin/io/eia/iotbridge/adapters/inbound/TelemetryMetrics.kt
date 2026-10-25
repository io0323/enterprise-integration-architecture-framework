package io.eia.iotbridge.adapters.inbound

import io.eia.iotbridge.domain.RejectionReason
import io.eia.iotbridge.domain.Telemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter

/**
 * iot-bridge のメトリクス(ADR-0039 §4・§7・§8)。OTLP → Collector → Prometheus。ラベルは数が限られたものだけにする
 * (デバイスの ID はラベルにしない)。
 *
 * | 名前 | 種類 | 属性 | 用途 |
 * |---|---|---|---|
 * | `eia.iot.telemetry.received` | counter | `payload_version` | 発行した件数。古い版をやめる判断の材料(版ごとの受信がゼロか。§4) |
 * | `eia.iot.telemetry.rejected` | counter | `reason` | DLQ に隔離した件数(`UNSUPPORTED_PAYLOAD_VERSION` など) |
 * | `eia.iot.telemetry.clock_suspect` | counter | `payload_version` | デバイスの時刻が疑わしいもの(§7) |
 * | `eia.iot.telemetry.lag` | histogram(秒) | `payload_version` | 受け取った時刻 − デバイスの時刻(遅れて届いた大きさ。§8)。時刻が疑わしいものは除く |
 * | `eia.iot.telemetry.retries` | counter | — | Kafka に送れず、PUBACK を返さずに送り直した回数 |
 */
public class TelemetryMetrics(
    meter: Meter,
) {
    private val received: LongCounter =
        meter
            .counterBuilder("eia.iot.telemetry.received")
            .setDescription("発行したテレメトリの件数(形式の版ごと)")
            .setUnit("{message}")
            .build()
    private val rejected: LongCounter =
        meter
            .counterBuilder("eia.iot.telemetry.rejected")
            .setDescription("DLQ に隔離したテレメトリの件数(理由ごと)")
            .setUnit("{message}")
            .build()
    private val clockSuspect: LongCounter =
        meter
            .counterBuilder("eia.iot.telemetry.clock_suspect")
            .setDescription("デバイスの時刻が疑わしいテレメトリの件数")
            .setUnit("{message}")
            .build()
    private val lag: DoubleHistogram =
        meter
            .histogramBuilder("eia.iot.telemetry.lag")
            .setDescription("受け取った時刻とデバイスの時刻の差")
            .setUnit("s")
            .build()
    private val retries: LongCounter =
        meter
            .counterBuilder("eia.iot.telemetry.retries")
            .setDescription("Kafka に送れずに送り直した回数")
            .setUnit("{attempt}")
            .build()

    internal fun published(telemetry: Telemetry) {
        val version = Attributes.of(PAYLOAD_VERSION, telemetry.payloadVersion.toLong())
        received.add(1, version)
        val deviceTime = telemetry.deviceTime
        if (telemetry.clockSuspect || deviceTime == null) {
            clockSuspect.add(1, version)
        } else {
            lag.record((telemetry.receivedAt - deviceTime).inWholeMilliseconds.coerceAtLeast(0) / MILLIS_PER_SECOND, version)
        }
    }

    internal fun rejected(reason: RejectionReason) = rejected.add(1, Attributes.of(REASON, reason.name))

    internal fun rejected(reason: String) = rejected.add(1, Attributes.of(REASON, reason))

    internal fun retried() = retries.add(1)

    private companion object {
        val PAYLOAD_VERSION: AttributeKey<Long> = AttributeKey.longKey("payload_version")
        val REASON: AttributeKey<String> = AttributeKey.stringKey("reason")
        const val MILLIS_PER_SECOND = 1000.0
    }
}
