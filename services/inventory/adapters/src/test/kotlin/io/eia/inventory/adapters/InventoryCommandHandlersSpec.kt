package io.eia.inventory.adapters

import io.eia.inventory.adapters.inbound.InventoryCommandHandlers
import io.eia.inventory.adapters.inbound.ReleaseStockV1
import io.eia.inventory.adapters.inbound.ReserveStockV1
import io.eia.inventory.adapters.inbound.StockLineV1
import io.eia.inventory.adapters.out.persistence.TransientSqlError
import io.eia.inventory.application.port.inbound.CommandEnvelope
import io.eia.inventory.application.port.inbound.CommandOutcome
import io.eia.inventory.application.port.inbound.ReleaseStockUseCase
import io.eia.inventory.application.port.inbound.ReserveStockUseCase
import io.eia.inventory.domain.StockLine
import io.eia.platform.messagingkafka.ConsumedEvent
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.Handled
import io.eia.platform.messagingkafka.HandlingFailure
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.trace.TraceParent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val ID = Uuid.parse("0199b6a0-0000-7000-8000-000000000001")

private fun <T> event(
    value: T,
    topic: String,
): ConsumedEvent<T> =
    ConsumedEvent(
        EventMetadata(
            ID,
            "/sales/order-service",
            topic.removeSuffix(".v1"),
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

/** 受け取った引数を記録し、[result] を返すユースケース。 */
private class Fake(
    var result: Result<CommandOutcome, DomainError> = ok(CommandOutcome.PROCESSED),
) : ReserveStockUseCase,
    ReleaseStockUseCase {
    val calls = mutableListOf<Pair<CommandEnvelope, List<StockLine>>>()

    override suspend fun invoke(
        envelope: CommandEnvelope,
        lines: List<StockLine>,
    ): Result<CommandOutcome, DomainError> = result.also { calls += envelope to lines }

    override suspend fun invoke(envelope: CommandEnvelope): Result<CommandOutcome, DomainError> =
        result.also {
            calls +=
                envelope to emptyList()
        }
}

class InventoryCommandHandlersSpec :
    FunSpec({
        val reserveTopic = InventoryCommandHandlers.RESERVE.name

        test("引当の指示を、ce_id・トピック・Saga ID・明細のユースケースの入力にする") {
            val fake = Fake()
            val handlers = InventoryCommandHandlers(fake, fake)
            handlers.onReserve(event(ReserveStockV1("saga-1", "order-1", listOf(StockLineV1(1, "SKU-1", 2))), reserveTopic)) shouldBe
                Result.Ok(Handled.PROCESSED)
            fake.calls.single() shouldBe
                (CommandEnvelope(ID.toString(), reserveTopic, "saga-1", "order-1") to listOf(StockLine(1, "SKU-1", 2)))

            fake.result = ok(CommandOutcome.DUPLICATE)
            handlers.onRelease(event(ReleaseStockV1("saga-1", "order-1"), InventoryCommandHandlers.RELEASE.name)) shouldBe
                Result.Ok(Handled.DUPLICATE)
        }

        test("明細の誤り(空・数量 0・SKU が空)は、ユースケースを呼ばずに Rejected(DLQ)") {
            val fake = Fake()
            val handlers = InventoryCommandHandlers(fake, fake)
            listOf(emptyList(), listOf(StockLineV1(1, "SKU-1", 0)), listOf(StockLineV1(1, "", 1))).forEach { lines ->
                (handlers.onReserve(event(ReserveStockV1("saga-1", "order-1", lines), reserveTopic)) as Result.Err)
                    .error
                    .shouldBeInstanceOf<HandlingFailure.Rejected>()
                    .code shouldBe "validation_failed"
            }
            fake.calls shouldBe emptyList()
        }

        test("ユースケースのエラーを、Consumer の失敗の種類に写す(ADR-0028 §2)") {
            val fake = Fake()
            val handlers = InventoryCommandHandlers(fake, fake)

            suspend fun failure(error: DomainError): HandlingFailure {
                fake.result = err(error)
                return (
                    handlers.onRelease(
                        event(ReleaseStockV1("saga-1", "order-1"), InventoryCommandHandlers.RELEASE.name),
                    ) as Result.Err
                ).error
            }
            failure(TransientSqlError("PostgreSQL: SQLSTATE 40001")).shouldBeInstanceOf<HandlingFailure.Transient>()
            failure(UnavailableError("PostgreSQL: SQLSTATE 08006")).shouldBeInstanceOf<HandlingFailure.Unavailable>()
            failure(UnexpectedError("PostgreSQL: SQLSTATE 23514")).shouldBeInstanceOf<HandlingFailure.Rejected>()
            failure(ValidationError.of("sagaId", "空")).shouldBeInstanceOf<HandlingFailure.Rejected>()
        }

        test("購読はコマンドの 2 つのトピックで、連携 ID は INT-INVENTORY-001、Consumer Group は inventory.command") {
            val writerSchemas =
                WriterSchemas(
                    ApicurioRegistryClient(
                        SchemaRegistryConfig("http://registry.test/apis/registry/v3"),
                        HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
                    ),
                )
            val subscriptions = InventoryCommandHandlers(Fake(), Fake()).subscriptions(writerSchemas)
            subscriptions.map { it.topic.name } shouldBe listOf("inventory.stock.cmd-reserve.v1", "inventory.stock.cmd-release.v1")
            subscriptions.map { it.integrationId }.toSet() shouldBe setOf("INT-INVENTORY-001")
            InventoryCommandHandlers.GROUP_ID shouldBe "inventory.command"
        }
    })
