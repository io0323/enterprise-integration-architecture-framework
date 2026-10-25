package io.eia.iotbridge.domain

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * デバイスの識別(ADR-0039 §3)。MQTT のトピック `devices/{tenant}/{deviceId}/telemetry` から取る。トピックは ACL で守られている
 * (デバイスは自分のトピックにしか書けない)ので、ペイロードの値ではなくトピックを信じる。
 *
 * @property key Kafka のパーティションのキー `{tenant}/{deviceId}`(デバイスの ID はテナントの中でだけ一意。ADR-0039 §6)
 */
public data class DeviceAddress(
    val tenant: String,
    val deviceId: String,
) {
    val key: String get() = "$tenant/$deviceId"

    public companion object {
        private val TOPIC = Regex("^devices/([a-z0-9][a-z0-9-]*)/([a-z0-9][a-z0-9-]*)/telemetry$")

        /** テレメトリのトピックを読む。形が違えば [TelemetryRejection](`INVALID_TOPIC`)。 */
        public fun fromTopic(topic: String): Result<DeviceAddress, TelemetryRejection> {
            val match = TOPIC.matchEntire(topic) ?: return err(TelemetryRejection(RejectionReason.INVALID_TOPIC, "topic"))
            return ok(DeviceAddress(match.groupValues[1], match.groupValues[2]))
        }
    }
}
