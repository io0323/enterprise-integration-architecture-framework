package io.eia.iotbridge.domain

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 形式の版によらずにそろえたテレメトリ(`iot.telemetry.reported.v1` の 1 件。contracts/avro/iot/TelemetryReported.avsc)。
 *
 * @property deviceTime デバイスの時計での時刻(送られなかったら null)。分析の発生時刻に使う(ADR-0039 §7)
 * @property receivedAt iot-bridge が MQTT から受け取った時刻。ce_time・Kafka のレコードの時刻(保持期間の起点)・遅れの監視に使う
 * @property clockSuspect [deviceTime] が疑わしい([ClockCheck])。値は直さない
 */
public data class Telemetry(
    val address: DeviceAddress,
    val bootId: String,
    val seq: Long,
    val payloadVersion: Int,
    val deviceTime: Instant?,
    val receivedAt: Instant,
    val clockSuspect: Boolean,
    val readings: List<Reading>,
    val batteryPercent: Int?,
) {
    /**
     * メッセージを識別する名前 `{tenant}/{deviceId}/{boot}/{seq}`(ADR-0039 §5)。ce_id はこの名前から作る UUID の版 5。
     * 起動のたびに boot が変わるので、番号(seq)が振り直されても以前のメッセージと重ならない。
     */
    val identity: String get() = "${address.key}/$bootId/$seq"
}

/** デバイスの時計の疑わしさの判定(ADR-0039 §7)。 */
public object ClockCheck {
    /** 受け取った時刻より、この時間を超えて未来なら疑う。 */
    public val FUTURE_TOLERANCE: kotlin.time.Duration = 5.minutes

    /** これより前の時刻は、時計が初期化されたデバイスの典型の値とみなす。 */
    public val EARLIEST_PLAUSIBLE: Instant = Instant.parse("2020-01-01T00:00:00Z")

    /** 時刻がない・受け取った時刻より [FUTURE_TOLERANCE] を超えて未来・[EARLIEST_PLAUSIBLE] より前なら true。過去にずれているだけなら疑わない(遅れて届いた)。 */
    public fun suspect(
        deviceTime: Instant?,
        receivedAt: Instant,
    ): Boolean = deviceTime == null || deviceTime > receivedAt + FUTURE_TOLERANCE || deviceTime < EARLIEST_PLAUSIBLE
}

/** 形式の版ごとのペイロードを 1 つの形([Telemetry])にそろえる(ADR-0039 §4)。 */
public object TelemetryNormalization {
    /** 版 1 の温度・湿度を、版 2 の測定値の名前と単位(SenML の単位名)にする。 */
    public const val TEMPERATURE: String = "temperature"
    public const val HUMIDITY: String = "humidity"
    public const val CELSIUS: String = "Cel"
    public const val RELATIVE_HUMIDITY: String = "%RH"

    public fun normalize(
        address: DeviceAddress,
        payload: DevicePayload,
        receivedAt: Instant,
    ): Telemetry {
        val (deviceTime, readings, battery) =
            when (payload) {
                is DevicePayload.V1 -> {
                    Triple(
                        payload.timestampSeconds?.let(Instant::fromEpochSeconds),
                        listOfNotNull(
                            payload.temperature?.let { Reading(TEMPERATURE, it, CELSIUS) },
                            payload.humidity?.let { Reading(HUMIDITY, it, RELATIVE_HUMIDITY) },
                        ),
                        null,
                    )
                }

                is DevicePayload.V2 -> {
                    Triple(payload.time, payload.readings, payload.battery)
                }
            }
        return Telemetry(
            address = address,
            bootId = payload.boot,
            seq = payload.seq,
            payloadVersion = payload.version,
            deviceTime = deviceTime,
            receivedAt = receivedAt,
            clockSuspect = ClockCheck.suspect(deviceTime, receivedAt),
            readings = readings,
            batteryPercent = battery,
        )
    }
}
