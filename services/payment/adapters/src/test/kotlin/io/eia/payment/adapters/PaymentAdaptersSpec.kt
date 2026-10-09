package io.eia.payment.adapters

import com.github.avrokotlin.avro4k.Avro
import io.eia.payment.adapters.inbound.AuthorizePaymentV1
import io.eia.payment.adapters.inbound.MoneyV1
import io.eia.payment.adapters.inbound.PaymentCommandHandlers
import io.eia.payment.adapters.inbound.VoidPaymentV1
import io.eia.payment.adapters.out.outbox.PaymentAuthorizedV1
import io.eia.payment.adapters.out.outbox.PaymentDeclineReasonV1
import io.eia.payment.adapters.out.outbox.PaymentDeclinedV1
import io.eia.payment.adapters.out.outbox.PaymentEventSchemas
import io.eia.payment.adapters.out.outbox.PaymentVoidOutcomeV1
import io.eia.payment.adapters.out.outbox.PaymentVoidedV1
import io.eia.payment.adapters.out.persistence.TransientSqlError
import io.eia.payment.adapters.out.persistence.UuidV7AuthorizationIds
import io.eia.payment.application.port.inbound.AuthorizePaymentUseCase
import io.eia.payment.application.port.inbound.CommandEnvelope
import io.eia.payment.application.port.inbound.CommandOutcome
import io.eia.payment.application.port.inbound.VoidPaymentUseCase
import io.eia.payment.domain.Amount
import io.eia.platform.messagingkafka.ConsumedEvent
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.Handled
import io.eia.platform.messagingkafka.HandlingFailure
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.trace.TraceParent
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

private fun <T> event(value: T): ConsumedEvent<T> =
    ConsumedEvent(
        EventMetadata(
            ID,
            "/sales/order-service",
            "payment.payment.cmd-authorize",
            Instant.parse("2026-10-09T00:00:00Z"),
            (TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01") as Result.Ok).value,
            (CorrelationId.parse("corr-1") as Result.Ok).value,
        ),
        "saga-1",
        value,
        "payment.payment.cmd-authorize.v1",
        0,
        0,
    )

private class Fake(
    var result: Result<CommandOutcome, DomainError> = ok(CommandOutcome.PROCESSED),
) : AuthorizePaymentUseCase,
    VoidPaymentUseCase {
    val calls = mutableListOf<Any>()

    override suspend fun invoke(
        envelope: CommandEnvelope,
        customerId: String,
        amount: Amount,
    ): Result<CommandOutcome, DomainError> = result.also { calls += Triple(envelope, customerId, amount) }

    override suspend fun invoke(envelope: CommandEnvelope): Result<CommandOutcome, DomainError> = result.also { calls += envelope }
}

class PaymentAdaptersSpec :
    FunSpec({
        test("返事のスキーマは契約のファイルと同じで、返事とコマンドの型を契約のスキーマで読み書きできる") {
            PaymentEventSchemas.subjects.map { it.topic } shouldBe
                listOf("payment.payment.authorized.v1", "payment.payment.declined.v1", "payment.payment.voided.v1")
            PaymentEventSchemas.paymentAuthorized.schema shouldBe contract("payment/PaymentAuthorized.avsc")
            PaymentEventSchemas.paymentDeclined.schema shouldBe contract("payment/PaymentDeclined.avsc")
            PaymentEventSchemas.paymentVoided.schema shouldBe contract("payment/PaymentVoided.avsc")
            roundTrip(
                PaymentEventSchemas.paymentAuthorized.schema,
                PaymentAuthorizedV1.serializer(),
                PaymentAuthorizedV1("s", "o", "a"),
            ) shouldBe
                PaymentAuthorizedV1("s", "o", "a")
            val declined = PaymentDeclinedV1("s", "o", PaymentDeclineReasonV1.LIMIT_EXCEEDED)
            roundTrip(PaymentEventSchemas.paymentDeclined.schema, PaymentDeclinedV1.serializer(), declined) shouldBe declined
            val voided = PaymentVoidedV1("s", "o", PaymentVoidOutcomeV1.NOT_AUTHORIZED)
            roundTrip(PaymentEventSchemas.paymentVoided.schema, PaymentVoidedV1.serializer(), voided) shouldBe voided
            val authorize = AuthorizePaymentV1("s", "o", "c", MoneyV1(1500, "JPY"))
            roundTrip(contract("payment/AuthorizePayment.avsc"), AuthorizePaymentV1.serializer(), authorize) shouldBe authorize
            roundTrip(contract("payment/VoidPayment.avsc"), VoidPaymentV1.serializer(), VoidPaymentV1("s", "o")) shouldBe
                VoidPaymentV1("s", "o")
            authorize.toString() shouldNotContain "1500"
        }

        test("承認の指示をユースケースの入力にする。金額の誤りは呼ばずに Rejected") {
            val fake = Fake()
            val handlers = PaymentCommandHandlers(fake, fake)
            handlers.onAuthorize(event(AuthorizePaymentV1("saga-1", "order-1", "cust-1", MoneyV1(1500, "JPY")))) shouldBe
                Result.Ok(Handled.PROCESSED)
            fake.calls.single() shouldBe
                Triple(
                    CommandEnvelope(ID.toString(), "payment.payment.cmd-authorize.v1", "saga-1", "order-1"),
                    "cust-1",
                    Amount(1500, "JPY"),
                )
            (handlers.onAuthorize(event(AuthorizePaymentV1("s", "o", "c", MoneyV1(-1, "jpy")))) as Result.Err)
                .error
                .shouldBeInstanceOf<HandlingFailure.Rejected>()
            fake.calls.size shouldBe 1
        }

        test("エラーの写し方: Transient / Unavailable / Rejected。DUPLICATE は Handled.DUPLICATE") {
            val fake = Fake()
            val handlers = PaymentCommandHandlers(fake, fake)

            suspend fun failure(error: DomainError): HandlingFailure {
                fake.result = err(error)
                return (handlers.onVoid(event(VoidPaymentV1("s", "o"))) as Result.Err).error
            }
            failure(TransientSqlError("40001")).shouldBeInstanceOf<HandlingFailure.Transient>()
            failure(UnavailableError("08006")).shouldBeInstanceOf<HandlingFailure.Unavailable>()
            failure(UnexpectedError("23514")).shouldBeInstanceOf<HandlingFailure.Rejected>()
            fake.result = ok(CommandOutcome.DUPLICATE)
            handlers.onVoid(event(VoidPaymentV1("s", "o"))) shouldBe Result.Ok(Handled.DUPLICATE)
            PaymentCommandHandlers.GROUP_ID shouldBe "payment.command"
        }

        test("承認 ID は UUIDv7 で、毎回違う") {
            val ids = UuidV7AuthorizationIds()
            val first = Uuid.parse(ids.next())
            (first != Uuid.parse(ids.next())) shouldBe true
            first.toString()[14] shouldBe '7'
        }
    })
