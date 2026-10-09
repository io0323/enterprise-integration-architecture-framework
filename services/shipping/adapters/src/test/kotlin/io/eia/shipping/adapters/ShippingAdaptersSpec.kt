package io.eia.shipping.adapters

import com.github.avrokotlin.avro4k.Avro
import io.eia.platform.messagingkafka.ConsumedEvent
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.Handled
import io.eia.platform.messagingkafka.HandlingFailure
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.trace.TraceParent
import io.eia.shipping.adapters.inbound.AddressV1
import io.eia.shipping.adapters.inbound.ArrangeShipmentV1
import io.eia.shipping.adapters.inbound.CancelShipmentV1
import io.eia.shipping.adapters.inbound.ShipmentLineV1
import io.eia.shipping.adapters.inbound.ShippingCommandHandlers
import io.eia.shipping.adapters.out.outbox.ShipmentCancelOutcomeV1
import io.eia.shipping.adapters.out.outbox.ShipmentCancelledV1
import io.eia.shipping.adapters.out.outbox.ShipmentRejectedV1
import io.eia.shipping.adapters.out.outbox.ShipmentRejectionReasonV1
import io.eia.shipping.adapters.out.outbox.ShipmentShippedV1
import io.eia.shipping.adapters.out.outbox.ShippingEventSchemas
import io.eia.shipping.adapters.out.persistence.TransientSqlError
import io.eia.shipping.adapters.out.persistence.UuidV7ShipmentStamps
import io.eia.shipping.application.port.inbound.ArrangeShipmentUseCase
import io.eia.shipping.application.port.inbound.CancelShipmentUseCase
import io.eia.shipping.application.port.inbound.CommandEnvelope
import io.eia.shipping.application.port.inbound.CommandOutcome
import io.eia.shipping.domain.Destination
import io.eia.shipping.domain.ShipmentLine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.KSerializer
import org.apache.avro.Schema
import java.io.File
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun contract(path: String): String = File(checkNotNull(System.getProperty("eia.contractsAvro")), path).readText()

private fun <T> roundTrip(
    schema: String,
    serializer: KSerializer<T>,
    value: T,
): T {
    val parsed = Schema.Parser().parse(schema)
    return Avro.decodeFromByteArray(parsed, serializer, Avro.encodeToByteArray(parsed, serializer, value))
}

private val ID = Uuid.parse("0199b6a0-0000-7000-8000-000000000001")
private val SHIPPED_AT = Instant.parse("2026-10-09T01:02:03.456789Z")
private val ADDRESS = AddressV1("JP", "100-0001", "千代田区", "千代田 1-1")

private fun <T> event(value: T): ConsumedEvent<T> =
    ConsumedEvent(
        EventMetadata(
            ID,
            "/sales/order-service",
            "shipping.shipment.cmd-arrange",
            Instant.parse("2026-10-09T00:00:00Z"),
            (TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01") as Result.Ok).value,
            (CorrelationId.parse("corr-1") as Result.Ok).value,
        ),
        "saga-1",
        value,
        "shipping.shipment.cmd-arrange.v1",
        0,
        0,
    )

private class Fake(
    var result: Result<CommandOutcome, DomainError> = ok(CommandOutcome.PROCESSED),
) : ArrangeShipmentUseCase,
    CancelShipmentUseCase {
    val calls = mutableListOf<Any>()

    override suspend fun invoke(
        envelope: CommandEnvelope,
        destination: Destination,
        lines: List<ShipmentLine>,
    ): Result<CommandOutcome, DomainError> = result.also { calls += Triple(envelope, destination, lines) }

    override suspend fun invoke(envelope: CommandEnvelope): Result<CommandOutcome, DomainError> = result.also { calls += envelope }
}

class ShippingAdaptersSpec :
    FunSpec({
        test("返事のスキーマは契約のファイルと同じで、返事とコマンドの型を契約のスキーマで読み書きできる(時刻はマイクロ秒)") {
            ShippingEventSchemas.subjects.map { it.topic } shouldBe
                listOf("shipping.shipment.shipped.v1", "shipping.shipment.rejected.v1", "shipping.shipment.cancelled.v1")
            ShippingEventSchemas.shipmentShipped.schema shouldBe contract("shipping/ShipmentShipped.avsc")
            ShippingEventSchemas.shipmentRejected.schema shouldBe contract("shipping/ShipmentRejected.avsc")
            ShippingEventSchemas.shipmentCancelled.schema shouldBe contract("shipping/ShipmentCancelled.avsc")
            val shipped = ShipmentShippedV1("s", "o", "ship-1", SHIPPED_AT)
            roundTrip(ShippingEventSchemas.shipmentShipped.schema, ShipmentShippedV1.serializer(), shipped) shouldBe shipped
            val rejected = ShipmentRejectedV1("s", "o", ShipmentRejectionReasonV1.UNSUPPORTED_DESTINATION)
            roundTrip(ShippingEventSchemas.shipmentRejected.schema, ShipmentRejectedV1.serializer(), rejected) shouldBe rejected
            val cancelled = ShipmentCancelledV1("s", "o", ShipmentCancelOutcomeV1.ALREADY_SHIPPED)
            roundTrip(ShippingEventSchemas.shipmentCancelled.schema, ShipmentCancelledV1.serializer(), cancelled) shouldBe cancelled
            val arrange = ArrangeShipmentV1("s", "o", ADDRESS.copy(line2 = "101"), listOf(ShipmentLineV1(1, "SKU-1", 2)))
            roundTrip(contract("shipping/ArrangeShipment.avsc"), ArrangeShipmentV1.serializer(), arrange) shouldBe arrange
            roundTrip(contract("shipping/CancelShipment.avsc"), CancelShipmentV1.serializer(), CancelShipmentV1("s", "o")) shouldBe
                CancelShipmentV1("s", "o")
            ADDRESS.toString() shouldNotContain "千代田"
        }

        test("手配の指示を、届け先の国と明細のユースケースの入力にする。国・明細の誤りは呼ばずに Rejected") {
            val fake = Fake()
            val handlers = ShippingCommandHandlers(fake, fake)
            handlers.onArrange(event(ArrangeShipmentV1("saga-1", "order-1", ADDRESS, listOf(ShipmentLineV1(1, "SKU-1", 2))))) shouldBe
                Result.Ok(Handled.PROCESSED)
            fake.calls.single() shouldBe
                Triple(
                    CommandEnvelope(ID.toString(), "shipping.shipment.cmd-arrange.v1", "saga-1", "order-1"),
                    Destination("JP"),
                    listOf(ShipmentLine(1, "SKU-1", 2)),
                )
            listOf(
                ArrangeShipmentV1("s", "o", ADDRESS.copy(countryCode = "jp"), listOf(ShipmentLineV1(1, "SKU-1", 1))),
                ArrangeShipmentV1("s", "o", ADDRESS, listOf(ShipmentLineV1(1, "SKU-1", 0))),
            ).forEach { command ->
                (handlers.onArrange(event(command)) as Result.Err).error.shouldBeInstanceOf<HandlingFailure.Rejected>()
            }
            fake.calls.size shouldBe 1
        }

        test("エラーの写し方: Transient / Unavailable / Rejected。DUPLICATE は Handled.DUPLICATE") {
            val fake = Fake()
            val handlers = ShippingCommandHandlers(fake, fake)

            suspend fun failure(error: DomainError): HandlingFailure {
                fake.result = err(error)
                return (handlers.onCancel(event(CancelShipmentV1("s", "o"))) as Result.Err).error
            }
            failure(TransientSqlError("40001")).shouldBeInstanceOf<HandlingFailure.Transient>()
            failure(UnavailableError("08006")).shouldBeInstanceOf<HandlingFailure.Unavailable>()
            failure(UnexpectedError("23514")).shouldBeInstanceOf<HandlingFailure.Rejected>()
            fake.result = ok(CommandOutcome.DUPLICATE)
            handlers.onCancel(event(CancelShipmentV1("s", "o"))) shouldBe Result.Ok(Handled.DUPLICATE)
            ShippingCommandHandlers.GROUP_ID shouldBe "shipping.command"
        }

        test("出荷の時刻はマイクロ秒に切り詰める(DB と契約の精度。返し直した返事と同じにする)。出荷 ID は UUIDv7") {
            val stamp = UuidV7ShipmentStamps(FixedClock(Instant.parse("2026-10-09T01:02:03.456789999Z"))).next()
            stamp.shippedAt shouldBe SHIPPED_AT
            Uuid.parse(stamp.shipmentId).toString()[14] shouldBe '7'
        }
    })
