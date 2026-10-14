package io.eia.batchetl.application

import io.eia.batchetl.application.port.inbound.IngestReport
import io.eia.batchetl.application.port.outbound.EventBatch
import io.eia.batchetl.application.port.outbound.OrderEventSource
import io.eia.batchetl.application.port.outbound.OrderMart
import io.eia.batchetl.application.port.outbound.RawOrderStore
import io.eia.batchetl.application.port.outbound.SourcePosition
import io.eia.batchetl.application.usecase.BuildDailyOrderMartService
import io.eia.batchetl.application.usecase.IngestOrderEventsService
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.OrderEvent
import io.eia.batchetl.domain.OrderRecord
import io.eia.batchetl.domain.OrderStatus
import io.eia.batchetl.domain.ShippingAddress
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private class FakeSource(
    private val batches: List<EventBatch>,
) : OrderEventSource {
    var startedAt: SourcePosition? = null

    override suspend fun drain(
        start: SourcePosition?,
        load: suspend (EventBatch) -> Result<Unit, DomainError>,
    ): Result<Unit, DomainError> {
        startedAt = start
        for (batch in batches) {
            val loaded = load(batch)
            if (loaded is Result.Err) return loaded
        }
        return ok(Unit)
    }
}

private class FakeRaw(
    var stored: SourcePosition? = null,
    val failAfter: Int = Int.MAX_VALUE,
) : RawOrderStore {
    val records = mutableListOf<OrderRecord>()

    override suspend fun position(): Result<SourcePosition?, DomainError> = ok(stored)

    override suspend fun append(
        records: List<OrderRecord>,
        position: SourcePosition,
    ): Result<Unit, DomainError> {
        if (this.records.size >= failAfter) return err(UnavailableError("db"))
        this.records += records
        stored = position
        return ok(Unit)
    }
}

private class FakeMart(
    private val dirty: List<BusinessDay>,
    private val failOn: BusinessDay? = null,
) : OrderMart {
    val rebuilt = mutableListOf<BusinessDay>()

    override suspend fun dirtyDays(): Result<List<BusinessDay>, DomainError> =
        if (failOn == null &&
            dirty.isEmpty()
        ) {
            ok(emptyList())
        } else {
            ok(dirty)
        }

    override suspend fun rebuild(day: BusinessDay): Result<Int, DomainError> {
        if (day == failOn) return err(UnavailableError("db"))
        rebuilt += day
        return ok(day.toString().takeLast(2).toInt())
    }
}

class BatchEtlUseCasesSpec :
    FunSpec({
        val address = ShippingAddress("JP", "100-0001", "東京都", "千代田区", "千代田1-1", null)
        val placed = OrderEvent.Placed("o-1", "c-1", Instant.parse("2026-10-13T01:00:00Z"), 1200, "JPY", 1, address)
        val shipped = OrderEvent.StatusChanged("o-1", OrderStatus.SHIPPED, 2, Instant.parse("2026-10-13T02:00:00Z"))
        val batches =
            listOf(
                EventBatch(listOf(placed), SourcePosition("p1"), 0),
                EventBatch(listOf(shipped), SourcePosition("p2"), 1),
            )

        test("前回の位置から取り込み、住所を地域に変えてから保存し、位置を進める") {
            val source = FakeSource(batches)
            val raw = FakeRaw(stored = SourcePosition("p0"))

            IngestOrderEventsService(source, raw)().ok() shouldBe IngestReport(loaded = 2, deadLettered = 1)

            source.startedAt shouldBe SourcePosition("p0")
            raw.stored shouldBe SourcePosition("p2")
            raw.records[0]
                .shouldBeInstanceOf<OrderRecord.Placed>()
                .area.postalPrefix shouldBe "100"
            raw.records[1].shouldBeInstanceOf<OrderRecord.StatusChanged>()
        }

        test("保存に失敗したら、そこで止めて位置を進めない") {
            val raw = FakeRaw(failAfter = 1)

            IngestOrderEventsService(FakeSource(batches), raw)().shouldBeInstanceOf<Result.Err<*>>()

            raw.stored shouldBe SourcePosition("p1")
        }

        test("実行の日と変わった過去の日を作り直し、古すぎる日は報告だけ。途中で失敗したらそこで止める") {
            val day = BusinessDay.parse("2026-10-13").ok()
            val mart = FakeMart(listOf(day.minusDays(2), day.minusDays(40)))

            val report = BuildDailyOrderMartService(mart)(day).ok()

            report.rebuilt shouldBe listOf(day.minusDays(2) to 11, day to 13)
            report.tooOld shouldBe listOf(day.minusDays(40))
            BuildDailyOrderMartService(
                FakeMart(listOf(day.minusDays(2)), failOn = day.minusDays(2)),
            )(day).shouldBeInstanceOf<Result.Err<*>>()
        }
    })
