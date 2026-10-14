package io.eia.batchetl.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 注文のイベントを、取り込みを始めた時点の末尾まで分析の DB に取り込む(Incremental Load。ADR-0031 §4)。
 * 何度呼んでも、同じイベントは 1 回だけ取り込まれる(取り込んだ位置と行を同じトランザクションで確定する)。
 */
public interface IngestOrderEventsUseCase {
    public suspend operator fun invoke(): Result<IngestReport, DomainError>
}

/**
 * @property loaded 取り込んだイベントの件数(重複を含む)
 * @property deadLettered 読めずに DLQ に送ったイベントの件数
 */
public data class IngestReport(
    val loaded: Int,
    val deadLettered: Int,
)
