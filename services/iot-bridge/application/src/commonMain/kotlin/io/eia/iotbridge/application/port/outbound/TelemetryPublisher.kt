package io.eia.iotbridge.application.port.outbound

import io.eia.iotbridge.application.port.inbound.MessageContext
import io.eia.iotbridge.domain.Telemetry
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * テレメトリの発行先(`iot.telemetry.reported.v1`。キーは `{tenant}/{deviceId}`。ADR-0039 §6)。
 * 戻ったときは、発行が確定している(Kafka が受け取りを確認した。呼び出し元はその後に MQTT の PUBACK を返す。ADR-0039 §2)。
 */
public interface TelemetryPublisher {
    public suspend fun publish(
        telemetry: Telemetry,
        context: MessageContext,
    ): Result<Unit, DomainError>
}
