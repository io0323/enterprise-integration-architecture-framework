package io.eia.iotbridge.application

import io.eia.iotbridge.application.port.inbound.InboundTelemetry
import io.eia.iotbridge.application.port.inbound.MessageContext
import io.eia.iotbridge.application.port.outbound.TelemetryPublisher
import io.eia.iotbridge.application.usecase.IngestTelemetryService
import io.eia.iotbridge.domain.DevicePayload
import io.eia.iotbridge.domain.RejectionReason
import io.eia.iotbridge.domain.Telemetry
import io.eia.iotbridge.domain.TelemetryRejection
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private val RECEIVED = Instant.parse("2026-10-25T00:00:00Z")

private class RecordingPublisher(
    private val result: Result<Unit, DomainError> = ok(Unit),
) : TelemetryPublisher {
    val published = mutableListOf<Pair<Telemetry, MessageContext>>()

    override suspend fun publish(
        telemetry: Telemetry,
        context: MessageContext,
    ): Result<Unit, DomainError> {
        published += telemetry to context
        return result
    }
}

private fun message(topic: String = "devices/t1/d-001/telemetry") =
    InboundTelemetry(
        topic = topic,
        payload = DevicePayload.V1("0123456789abcdef", 1, null, 20.0, null),
        receivedAt = RECEIVED,
        context = MessageContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "c-1"),
    )

class IngestTelemetrySpec :
    FunSpec({
        test("トピックのデバイスで 1 つの形にそろえて発行し、トレースの文脈を引き継ぐ") {
            val publisher = RecordingPublisher()

            val result = IngestTelemetryService(publisher)(message())

            val telemetry = result.shouldBeInstanceOf<Result.Ok<Telemetry>>().value
            telemetry.address.key shouldBe "t1/d-001"
            telemetry.receivedAt shouldBe RECEIVED
            publisher.published.single() shouldBe (telemetry to message().context)
        }

        test("トピックの形が違えば発行せずに INVALID_TOPIC(呼び出し元が DLQ に送る)") {
            val publisher = RecordingPublisher()

            val error =
                IngestTelemetryService(
                    publisher,
                )(message("devices/t1/d-001/other")).shouldBeInstanceOf<Result.Err<DomainError>>().error

            error.shouldBeInstanceOf<TelemetryRejection>().reason shouldBe RejectionReason.INVALID_TOPIC
            publisher.published.size shouldBe 0
        }

        test("発行の失敗はそのまま返す(Retryable なら呼び出し元が PUBACK を返さずに送り直す)") {
            val failure = UnavailableError("kafka")

            IngestTelemetryService(RecordingPublisher(err(failure)))(message()) shouldBe err(failure)
        }
    })
