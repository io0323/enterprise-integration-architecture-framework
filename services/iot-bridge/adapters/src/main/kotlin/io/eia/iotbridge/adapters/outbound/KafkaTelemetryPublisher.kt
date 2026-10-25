package io.eia.iotbridge.adapters.outbound

import io.eia.iotbridge.adapters.inbound.MessageIds
import io.eia.iotbridge.application.port.inbound.MessageContext
import io.eia.iotbridge.application.port.outbound.TelemetryPublisher
import io.eia.iotbridge.domain.Telemetry
import io.eia.platform.messagingkafka.EventIdentity
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError

/**
 * [TelemetryPublisher] の Kafka の実装(`iot.telemetry.reported.v1`。ADR-0039)。
 *
 * - キーは `{tenant}/{deviceId}`(§6)。
 * - `ce_id` は `{tenant}/{deviceId}/{boot}/{seq}` の UUID の版 5([MessageIds]。同じメッセージは同じ ce_id。§5)。
 * - `ce_time` と Kafka のレコードの時刻は受け取った時刻(`receivedAt`。デバイスの時刻にすると、遅れて届いたデータがすぐに保持期間を過ぎる。§7)。
 * - 送信の完了(acks=all)まで待つので、戻ったときは発行が確定している(呼び出し元はその後に PUBACK を返す。§2)。
 * - トレース(PRODUCER の span の親)と Correlation ID は、コルーチンの文脈(入口で MQTT のユーザープロパティから引き継いだもの)を使う。
 *   [MessageContext] は Port の利用者のために渡されるが、ここでは文脈の側を正とする。
 */
public class KafkaTelemetryPublisher(
    private val producer: EventProducer,
    ids: SchemaIdBook,
) : TelemetryPublisher {
    private val serializer = IotEventSchemas.telemetryReportedSerializer(ids)

    override suspend fun publish(
        telemetry: Telemetry,
        context: MessageContext,
    ): Result<Unit, DomainError> =
        producer
            .send(
                serializer,
                telemetry.address.key,
                telemetry.toEvent(),
                EventIdentity(MessageIds.of(telemetry), telemetry.receivedAt),
            ).mapError { it.asDomainError() }
            .map { }

    public companion object {
        /** `ce_source`(contracts/asyncapi/iot-telemetry-events.v1.yaml)。 */
        public const val SOURCE: String = "/iot/iot-bridge"
    }
}

internal fun Telemetry.toEvent(): TelemetryReportedV1 =
    TelemetryReportedV1(
        tenant = address.tenant,
        deviceId = address.deviceId,
        bootId = bootId,
        seq = seq,
        payloadVersion = payloadVersion,
        deviceTime = deviceTime,
        receivedAt = receivedAt,
        clockSuspect = clockSuspect,
        readings = readings.map { ReadingV1(it.name, it.value, it.unit) },
        batteryPercent = batteryPercent,
    )
