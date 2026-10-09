package io.eia.order.adapters.saga

import com.github.avrokotlin.avro4k.Avro
import io.eia.order.adapters.inbound.kafka.PaymentAuthorizedV1
import io.eia.order.adapters.inbound.kafka.PaymentDeclineReasonV1
import io.eia.order.adapters.inbound.kafka.PaymentDeclinedV1
import io.eia.order.adapters.inbound.kafka.PaymentVoidOutcomeV1
import io.eia.order.adapters.inbound.kafka.PaymentVoidedV1
import io.eia.order.adapters.inbound.kafka.SagaReplyHandlers
import io.eia.order.adapters.inbound.kafka.ShipmentCancelOutcomeV1
import io.eia.order.adapters.inbound.kafka.ShipmentCancelledV1
import io.eia.order.adapters.inbound.kafka.ShipmentRejectedV1
import io.eia.order.adapters.inbound.kafka.ShipmentRejectionReasonV1
import io.eia.order.adapters.inbound.kafka.ShipmentShippedV1
import io.eia.order.adapters.inbound.kafka.StockRejectionReasonV1
import io.eia.order.adapters.inbound.kafka.StockReleaseOutcomeV1
import io.eia.order.adapters.inbound.kafka.StockReleasedV1
import io.eia.order.adapters.inbound.kafka.StockReservationRejectedV1
import io.eia.order.adapters.inbound.kafka.StockReservedV1
import io.eia.order.adapters.out.outbox.AddressV1
import io.eia.order.adapters.out.outbox.MoneyV1
import io.eia.order.adapters.out.outbox.OrderCancelledV1
import io.eia.order.adapters.out.outbox.OrderEventSchemas
import io.eia.order.adapters.out.saga.ArrangeShipmentV1
import io.eia.order.adapters.out.saga.AuthorizePaymentV1
import io.eia.order.adapters.out.saga.CancelShipmentV1
import io.eia.order.adapters.out.saga.ReleaseStockV1
import io.eia.order.adapters.out.saga.ReserveStockV1
import io.eia.order.adapters.out.saga.SagaMetrics
import io.eia.order.adapters.out.saga.SagaSchemas
import io.eia.order.adapters.out.saga.ShipmentLineV1
import io.eia.order.adapters.out.saga.StockLineV1
import io.eia.order.adapters.out.saga.UuidV7SagaIds
import io.eia.order.adapters.out.saga.VoidPaymentV1
import io.eia.order.application.port.inbound.HandleSagaReplyUseCase
import io.eia.order.application.port.inbound.ReplyOutcome
import io.eia.order.application.port.inbound.SagaReply
import io.eia.order.domain.OrderId
import io.eia.order.domain.Saga
import io.eia.order.domain.SagaFailure
import io.eia.order.domain.SagaSignal
import io.eia.order.domain.SagaState
import io.eia.platform.messagingkafka.ConsumedEvent
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.EventSubscription
import io.eia.platform.messagingkafka.Handled
import io.eia.platform.messagingkafka.HandlingFailure
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.trace.TraceParent
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
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

private fun <T> event(
    topic: String,
    value: T,
): ConsumedEvent<T> =
    ConsumedEvent(
        EventMetadata(
            ID,
            "/inventory/inventory-service",
            topic.substringBeforeLast(".v"),
            Instant.parse("2026-10-09T00:00:00Z"),
            (TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01") as Result.Ok).value,
            (CorrelationId.parse("corr-1") as Result.Ok).value,
        ),
        "saga-1",
        value,
        topic,
        0,
        0,
    )

private class FakeHandle(
    var result: Result<ReplyOutcome, DomainError> = ok(ReplyOutcome.PROCESSED),
) : HandleSagaReplyUseCase {
    val replies = mutableListOf<SagaReply>()

    override suspend fun invoke(reply: SagaReply): Result<ReplyOutcome, DomainError> = result.also { replies += reply }
}

/** 呼ばれない(返信の型の読み取りは Kafka の統合テストで確かめる)。 */
private val unusedSchemas =
    WriterSchemas(
        ApicurioRegistryClient(
            SchemaRegistryConfig("http://registry.invalid"),
            HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }),
        ),
    )

@Suppress("UNCHECKED_CAST")
private suspend fun <T> List<EventSubscription<*>>.deliver(value: T): Result<Handled, HandlingFailure> {
    val subscription = single { it.topic.name == topicOf(value) } as EventSubscription<T>
    return subscription.handler.handle(event(subscription.topic.name, value))
}

private fun topicOf(value: Any?): String =
    when (value) {
        is StockReservedV1 -> SagaReplyHandlers.STOCK_RESERVED
        is StockReservationRejectedV1 -> SagaReplyHandlers.STOCK_RESERVATION_REJECTED
        is StockReleasedV1 -> SagaReplyHandlers.STOCK_RELEASED
        is PaymentAuthorizedV1 -> SagaReplyHandlers.PAYMENT_AUTHORIZED
        is PaymentDeclinedV1 -> SagaReplyHandlers.PAYMENT_DECLINED
        is PaymentVoidedV1 -> SagaReplyHandlers.PAYMENT_VOIDED
        is ShipmentShippedV1 -> SagaReplyHandlers.SHIPMENT_SHIPPED
        is ShipmentRejectedV1 -> SagaReplyHandlers.SHIPMENT_REJECTED
        is ShipmentCancelledV1 -> SagaReplyHandlers.SHIPMENT_CANCELLED
        else -> fail("知らない返信: $value")
    }.name

class SagaAdaptersSpec :
    FunSpec({
        test("コマンドのスキーマは契約のファイルと同じで、コマンドと返信の型を契約のスキーマで読み書きできる") {
            SagaSchemas.subjects.map { it.topic } shouldBe
                listOf(
                    "inventory.stock.cmd-reserve.v1",
                    "inventory.stock.cmd-release.v1",
                    "payment.payment.cmd-authorize.v1",
                    "payment.payment.cmd-void.v1",
                    "shipping.shipment.cmd-arrange.v1",
                    "shipping.shipment.cmd-cancel.v1",
                )
            SagaSchemas.reserveStock.schema shouldBe contract("inventory/ReserveStock.avsc")
            SagaSchemas.arrangeShipment.schema shouldBe contract("shipping/ArrangeShipment.avsc")
            OrderEventSchemas.subjects.map { it.topic } shouldBe listOf("sales.order.created.v1", "sales.order.cancelled.v1")

            val commands =
                listOf(
                    Triple(
                        SagaSchemas.reserveStock.schema,
                        ReserveStockV1.serializer(),
                        ReserveStockV1("s", "o", listOf(StockLineV1(1, "SKU-1", 2))),
                    ),
                    Triple(SagaSchemas.releaseStock.schema, ReleaseStockV1.serializer(), ReleaseStockV1("s", "o")),
                    Triple(
                        SagaSchemas.authorizePayment.schema,
                        AuthorizePaymentV1.serializer(),
                        AuthorizePaymentV1("s", "o", "c", MoneyV1(1500, "JPY")),
                    ),
                    Triple(SagaSchemas.voidPayment.schema, VoidPaymentV1.serializer(), VoidPaymentV1("s", "o")),
                    Triple(
                        SagaSchemas.arrangeShipment.schema,
                        ArrangeShipmentV1.serializer(),
                        ArrangeShipmentV1("s", "o", AddressV1("JP", "100-0001", "千代田区", "千代田 1-1"), listOf(ShipmentLineV1(1, "SKU-1", 2))),
                    ),
                    Triple(SagaSchemas.cancelShipment.schema, CancelShipmentV1.serializer(), CancelShipmentV1("s", "o")),
                    Triple(
                        OrderEventSchemas.orderCancelled.schema,
                        OrderCancelledV1.serializer(),
                        OrderCancelledV1("o", Instant.parse("2026-10-09T00:00:00.123456Z"), "TIMED_OUT"),
                    ),
                )
            commands.forEach { (schema, serializer, value) ->
                @Suppress("UNCHECKED_CAST")
                roundTrip(schema, serializer as KSerializer<Any>, value) shouldBe value
            }
            AuthorizePaymentV1("s", "o", "cust-secret", MoneyV1(1500, "JPY")).toString().let {
                it shouldNotContain "cust-secret"
                it shouldNotContain "1500"
            }

            val replies =
                listOf(
                    Triple("inventory/StockReserved.avsc", StockReservedV1.serializer(), StockReservedV1("s", "o")),
                    Triple(
                        "inventory/StockReservationRejected.avsc",
                        StockReservationRejectedV1.serializer(),
                        StockReservationRejectedV1("s", "o", StockRejectionReasonV1.INSUFFICIENT_STOCK),
                    ),
                    Triple(
                        "inventory/StockReleased.avsc",
                        StockReleasedV1.serializer(),
                        StockReleasedV1("s", "o", StockReleaseOutcomeV1.RELEASED),
                    ),
                    Triple("payment/PaymentAuthorized.avsc", PaymentAuthorizedV1.serializer(), PaymentAuthorizedV1("s", "o", "a")),
                    Triple(
                        "payment/PaymentDeclined.avsc",
                        PaymentDeclinedV1.serializer(),
                        PaymentDeclinedV1("s", "o", PaymentDeclineReasonV1.LIMIT_EXCEEDED),
                    ),
                    Triple(
                        "payment/PaymentVoided.avsc",
                        PaymentVoidedV1.serializer(),
                        PaymentVoidedV1("s", "o", PaymentVoidOutcomeV1.VOIDED),
                    ),
                    Triple(
                        "shipping/ShipmentShipped.avsc",
                        ShipmentShippedV1.serializer(),
                        ShipmentShippedV1("s", "o", "ship-1", Instant.parse("2026-10-09T00:00:00.123456Z")),
                    ),
                    Triple(
                        "shipping/ShipmentRejected.avsc",
                        ShipmentRejectedV1.serializer(),
                        ShipmentRejectedV1("s", "o", ShipmentRejectionReasonV1.UNSUPPORTED_DESTINATION),
                    ),
                    Triple(
                        "shipping/ShipmentCancelled.avsc",
                        ShipmentCancelledV1.serializer(),
                        ShipmentCancelledV1("s", "o", ShipmentCancelOutcomeV1.ALREADY_SHIPPED),
                    ),
                )
            replies.forEach { (path, serializer, value) ->
                @Suppress("UNCHECKED_CAST")
                roundTrip(contract(path), serializer as KSerializer<Any>, value) shouldBe value
            }
        }

        test("返信を Saga ID と受け取ったものにして、ce_id とトピックを付けてユースケースに渡す(9 つの返信のトピック)") {
            val handle = FakeHandle()
            val subscriptions = SagaReplyHandlers(handle).subscriptions(unusedSchemas)
            subscriptions.map { it.integrationId }.toSet() shouldBe setOf("INT-INVENTORY-002", "INT-PAYMENT-002", "INT-SHIPPING-002")

            val cases =
                listOf(
                    StockReservedV1("saga-1", "o") to SagaSignal.STOCK_RESERVED,
                    StockReservationRejectedV1("saga-1", "o", StockRejectionReasonV1.INSUFFICIENT_STOCK) to
                        SagaSignal.STOCK_RESERVATION_REJECTED,
                    StockReleasedV1("saga-1", "o", StockReleaseOutcomeV1.NOT_RESERVED) to SagaSignal.STOCK_RELEASED,
                    PaymentAuthorizedV1("saga-1", "o", "a") to SagaSignal.PAYMENT_AUTHORIZED,
                    PaymentDeclinedV1("saga-1", "o", PaymentDeclineReasonV1.ALREADY_VOIDED) to SagaSignal.PAYMENT_DECLINED,
                    PaymentVoidedV1("saga-1", "o", PaymentVoidOutcomeV1.NOT_AUTHORIZED) to SagaSignal.PAYMENT_VOIDED,
                    ShipmentShippedV1("saga-1", "o", "ship-1", Instant.parse("2026-10-09T00:00:00Z")) to SagaSignal.SHIPMENT_SHIPPED,
                    ShipmentRejectedV1("saga-1", "o", ShipmentRejectionReasonV1.ALREADY_CANCELLED) to SagaSignal.SHIPMENT_REJECTED,
                    ShipmentCancelledV1("saga-1", "o", ShipmentCancelOutcomeV1.CANCELLED) to SagaSignal.SHIPMENT_CANCELLED,
                    ShipmentCancelledV1("saga-1", "o", ShipmentCancelOutcomeV1.NOT_ARRANGED) to SagaSignal.SHIPMENT_CANCELLED,
                    ShipmentCancelledV1("saga-1", "o", ShipmentCancelOutcomeV1.ALREADY_SHIPPED) to SagaSignal.SHIPMENT_ALREADY_SHIPPED,
                )
            cases.forEach { (value, _) -> subscriptions.deliver(value) shouldBe Result.Ok(Handled.PROCESSED) }
            handle.replies shouldBe cases.map { (value, signal) -> SagaReply(ID.toString(), topicOf(value), "saga-1", signal) }
        }

        test("読み手の知らない値(UNKNOWN)は、ユースケースを呼ばずに Rejected(DLQ)") {
            val handle = FakeHandle()
            val subscriptions = SagaReplyHandlers(handle).subscriptions(unusedSchemas)
            listOf(
                StockReservationRejectedV1("s", "o", StockRejectionReasonV1.UNKNOWN),
                StockReleasedV1("s", "o", StockReleaseOutcomeV1.UNKNOWN),
                PaymentDeclinedV1("s", "o", PaymentDeclineReasonV1.UNKNOWN),
                PaymentVoidedV1("s", "o", PaymentVoidOutcomeV1.UNKNOWN),
                ShipmentRejectedV1("s", "o", ShipmentRejectionReasonV1.UNKNOWN),
                ShipmentCancelledV1("s", "o", ShipmentCancelOutcomeV1.UNKNOWN),
            ).forEach { value ->
                val failure = (subscriptions.deliver(value) as Result.Err).error
                failure.shouldBeInstanceOf<HandlingFailure.Rejected>()
            }
            handle.replies shouldBe emptyList()
        }

        test("エラーの写し方: Retryable は Unavailable(読み直し)、NonRetryable は Rejected(DLQ)、DUPLICATE は Handled.DUPLICATE") {
            val handle = FakeHandle()
            val subscriptions = SagaReplyHandlers(handle).subscriptions(unusedSchemas)
            handle.result = err(UnavailableError("08006"))
            (subscriptions.deliver(StockReservedV1("s", "o")) as Result.Err).error.shouldBeInstanceOf<HandlingFailure.Unavailable>()
            handle.result = err(NotFoundError("saga", "s"))
            (subscriptions.deliver(StockReservedV1("s", "o")) as Result.Err).error.shouldBeInstanceOf<HandlingFailure.Rejected>()
            handle.result = ok(ReplyOutcome.DUPLICATE)
            subscriptions.deliver(StockReservedV1("s", "o")) shouldBe Result.Ok(Handled.DUPLICATE)
            SagaReplyHandlers.GROUP_ID shouldBe "order.saga"
        }

        test("メトリクス: 遷移(from・to・failure)、送り直し、上限を超えた送り直し(stalled)、無視した結果") {
            val reader = InMemoryMetricReader.create()
            val metrics =
                SagaMetrics(
                    SdkMeterProvider
                        .builder()
                        .registerMetricReader(reader)
                        .build()
                        .get("test"),
                    stallAfterResends = 2,
                )
            val orderId = (OrderId.parse("ord-1") as Result.Ok).value
            metrics.transitioned(Saga("s", orderId, SagaState.AUTHORIZING_PAYMENT), SagaState.RESERVING_STOCK)
            metrics.transitioned(Saga("s", orderId, SagaState.RELEASING_STOCK, SagaFailure.PAYMENT_DECLINED), SagaState.AUTHORIZING_PAYMENT)
            (1..3).forEach { metrics.resent(Saga("s", orderId, SagaState.RELEASING_STOCK, SagaFailure.TIMED_OUT, resends = it)) }
            metrics.ignored(SagaState.CANCELLING_SHIPMENT, SagaSignal.SHIPMENT_SHIPPED)

            val collected = reader.collectAllMetrics().associateBy { it.name }

            fun points(name: String) =
                collected
                    .getValue(name)
                    .longSumData.points
                    .associate { point -> point.attributes.asMap().mapKeys { it.key.key } to point.value }

            points("eia.saga.transitions") shouldBe
                mapOf(
                    mapOf("from" to "RESERVING_STOCK", "to" to "AUTHORIZING_PAYMENT", "failure" to "none") to 1L,
                    mapOf("from" to "AUTHORIZING_PAYMENT", "to" to "RELEASING_STOCK", "failure" to "PAYMENT_DECLINED") to 1L,
                )
            points("eia.saga.resends") shouldBe mapOf(mapOf("state" to "RELEASING_STOCK") to 3L)
            points("eia.saga.stalled") shouldBe mapOf(mapOf("state" to "RELEASING_STOCK") to 1L)
            points("eia.saga.ignored") shouldBe mapOf(mapOf("state" to "CANCELLING_SHIPMENT", "signal" to "SHIPMENT_SHIPPED") to 1L)
        }

        test("Saga ID は UUIDv7 で、毎回違う") {
            val ids = UuidV7SagaIds()
            val first = Uuid.parse(ids.next())
            (first != Uuid.parse(ids.next())) shouldBe true
            first.toString()[14] shouldBe '7'
        }
    })
