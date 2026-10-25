package io.eia.iotbridge.domain

import kotlin.time.Instant

/**
 * デバイスが送ったペイロード(形式の版ごと。contracts/devices/telemetry.v{n}.schema.json)。形式のスキーマへの適合は、
 * JSON を読むところ(adapters)で確かめてから作る。版を足すときは、ここに型を足し、[TelemetryNormalization] で 1 つの形にそろえる。
 */
public sealed interface DevicePayload {
    public val version: Int
    public val boot: String
    public val seq: Long

    /** 版 1(古いファームウェア)。測定値は温度と湿度の決まった 2 つ、時刻は UNIX 秒。 */
    public data class V1(
        override val boot: String,
        override val seq: Long,
        val timestampSeconds: Long?,
        val temperature: Double?,
        val humidity: Double?,
    ) : DevicePayload {
        override val version: Int get() = 1
    }

    /** 版 2。測定値は名前・値・単位の一覧、時刻はミリ秒の精度。 */
    public data class V2(
        override val boot: String,
        override val seq: Long,
        val time: Instant?,
        val readings: List<Reading>,
        val battery: Int?,
    ) : DevicePayload {
        override val version: Int get() = 2
    }
}

/** 測定値。 */
public data class Reading(
    val name: String,
    val value: Double,
    val unit: String?,
)
