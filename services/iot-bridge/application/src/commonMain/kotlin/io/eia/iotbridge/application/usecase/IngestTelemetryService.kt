package io.eia.iotbridge.application.usecase

import io.eia.iotbridge.application.port.inbound.InboundTelemetry
import io.eia.iotbridge.application.port.inbound.IngestTelemetryUseCase
import io.eia.iotbridge.application.port.outbound.TelemetryPublisher
import io.eia.iotbridge.domain.DeviceAddress
import io.eia.iotbridge.domain.Telemetry
import io.eia.iotbridge.domain.TelemetryNormalization
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError

/** [IngestTelemetryUseCase] の実装。状態を持たない(1 件の受信から 1 件の発行。重複は消費者が ce_id で取り除く。ADR-0039 §5)。 */
public class IngestTelemetryService(
    private val publisher: TelemetryPublisher,
) : IngestTelemetryUseCase {
    override suspend fun invoke(message: InboundTelemetry): Result<Telemetry, DomainError> =
        DeviceAddress
            .fromTopic(message.topic)
            .mapError<_, _, DomainError> { it }
            .map { address -> TelemetryNormalization.normalize(address, message.payload, message.receivedAt) }
            .flatMap { telemetry -> publisher.publish(telemetry, message.context).map { telemetry } }
}
