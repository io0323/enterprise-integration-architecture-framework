package io.eia.order.application.usecase

import io.eia.order.application.port.inbound.RepublishOrderEventsCommand
import io.eia.order.application.port.inbound.RepublishOrderEventsUseCase
import io.eia.order.application.port.inbound.RepublishReport
import io.eia.order.application.port.outbound.OrderAuditTrail
import io.eia.order.application.port.outbound.OrderEventOutbox
import io.eia.order.application.port.outbound.RepublishMark
import io.eia.order.application.port.outbound.RepublishableOrder
import io.eia.order.application.port.outbound.RepublishableOrders
import io.eia.order.application.port.outbound.TransactionRunner
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * 注文のイベントの再発行(ADR-0046)。[batchSize] 件ごとに 1 つのトランザクションで Outbox に書く(同じ注文のイベントは同じトランザクションで順に書く)。
 *
 * 途中で失敗しても、書いた分は確定している。同じ範囲でやり直してよい(受信側が業務のキーで重複を除く)。監査は最後に 1 件だけ記録する。
 */
public class RepublishOrderEventsService(
    private val orders: RepublishableOrders,
    private val events: OrderEventOutbox,
    private val audit: OrderAuditTrail,
    private val transactions: TransactionRunner,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : RepublishOrderEventsUseCase {
    init {
        require(batchSize >= 1) { "batchSize は 1 以上にしてください: $batchSize" }
    }

    @Suppress("ReturnCount") // 検証・読み取り・書き込み・監査の失敗で返す
    override suspend fun invoke(command: RepublishOrderEventsCommand): Result<RepublishReport, DomainError> {
        if (command.from >= command.to) return err(ValidationError.of("from", "from は to より前にしてください"))
        val mark = RepublishMark(command.reason, command.runId)
        var total = 0L
        var after: String? = null
        while (true) {
            val page =
                when (val read = orders.page(command.from, command.to, after, batchSize)) {
                    is Result.Err -> return read
                    is Result.Ok -> read.value
                }
            if (page.isEmpty()) break
            if (command.execute) {
                val written = transactions.inTransaction { republish(page, mark) }
                if (written is Result.Err) return written
            }
            total += page.size
            after =
                page
                    .last()
                    .order.id.value
        }
        if (!command.execute) return ok(RepublishReport(total, executed = false))
        return when (
            val recorded =
                transactions.inTransaction {
                    audit.eventsRepublished(mark, command.from, command.to, total, command.requestedBy)
                }
        ) {
            is Result.Err -> recorded
            is Result.Ok -> ok(RepublishReport(total, executed = true))
        }
    }

    private suspend fun republish(
        page: List<RepublishableOrder>,
        mark: RepublishMark,
    ): Result<Unit, DomainError> {
        page.forEach { item ->
            val written = events.orderRepublished(item, mark)
            if (written is Result.Err) return written
        }
        return ok(Unit)
    }

    public companion object {
        public const val DEFAULT_BATCH_SIZE: Int = 100
    }
}
