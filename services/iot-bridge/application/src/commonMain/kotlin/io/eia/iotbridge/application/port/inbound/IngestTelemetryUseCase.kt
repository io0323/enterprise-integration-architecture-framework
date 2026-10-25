package io.eia.iotbridge.application.port.inbound

import io.eia.iotbridge.domain.DevicePayload
import io.eia.iotbridge.domain.Telemetry
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/**
 * MQTT から受け取った 1 件のテレメトリを、形式の版によらずに 1 つの形にそろえて社内に発行する(ADR-0039)。
 *
 * @return 発行したテレメトリ。トピックの形が違えば `TelemetryRejection`(NonRetryable。呼び出し元が DLQ に送る)、
 *   発行の失敗は `TelemetryPublisher` のエラー(Retryable なら呼び出し元が PUBACK を返さずに送り直す)
 */
public interface IngestTelemetryUseCase {
    public suspend operator fun invoke(message: InboundTelemetry): Result<Telemetry, DomainError>
}

/**
 * 受け取ったメッセージ。[payload] は形式のスキーマに適合したことを確かめたもの(adapters)。
 *
 * @property topic MQTT のトピック(デバイスの ID はここから取る)
 * @property receivedAt iot-bridge が受け取った時刻
 */
public data class InboundTelemetry(
    val topic: String,
    val payload: DevicePayload,
    val receivedAt: Instant,
    val context: MessageContext,
)

/**
 * トレースの文脈(MQTT v5 のユーザープロパティの `traceparent`・`correlationid`。INTEGRATION_STANDARDS §2)。
 * MQTT v3.1.1 のデバイスは送れないので、どちらも null でありうる(発行するところで新しく始める。ADR-0039 §10)。
 */
public data class MessageContext(
    val traceParent: String? = null,
    val correlationId: String? = null,
)
