package io.eia.batchetl.application.usecase

import io.eia.batchetl.application.port.inbound.IngestOrderEventsUseCase
import io.eia.batchetl.application.port.inbound.IngestReport
import io.eia.batchetl.application.port.outbound.OrderEventSource
import io.eia.batchetl.application.port.outbound.RawOrderStore
import io.eia.batchetl.domain.toRecord
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.onOk

/**
 * 前回の位置から末尾までを取り込む。住所は取り込む前に地域に変える(ETL。個人情報を分析の DB に入れない。ADR-0031 §4)。
 */
public class IngestOrderEventsService(
    private val source: OrderEventSource,
    private val raw: RawOrderStore,
) : IngestOrderEventsUseCase {
    override suspend fun invoke(): Result<IngestReport, DomainError> {
        var loaded = 0
        var deadLettered = 0
        return raw
            .position()
            .flatMap { start ->
                source.drain(start) { batch ->
                    raw.append(batch.events.map { it.toRecord() }, batch.next).onOk {
                        loaded += batch.events.size
                        deadLettered += batch.deadLettered
                    }
                }
            }.map { IngestReport(loaded, deadLettered) }
    }
}
