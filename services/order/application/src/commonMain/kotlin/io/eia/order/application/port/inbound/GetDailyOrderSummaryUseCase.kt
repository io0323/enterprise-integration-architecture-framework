package io.eia.order.application.port.inbound

import io.eia.order.domain.BusinessDay
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/** 1 日の注文の件数とハッシュを返す(`GET /v1/order-summaries/daily/{date}`。分析の週次の照合用。ADR-0031 §3)。 */
public interface GetDailyOrderSummaryUseCase {
    public suspend operator fun invoke(day: BusinessDay): Result<DailyOrderSummary, DomainError>
}

/**
 * 1 日の集計。個々の注文の内容は含まない。
 *
 * @property from 区間の始まり(含む)
 * @property until 区間の終わり(含まない)
 * @property digest 内容のハッシュ(SHA-256 の小文字の 16 進。作り方は `DailyOrderDigest` と契約)
 */
public data class DailyOrderSummary(
    val day: BusinessDay,
    val from: Instant,
    val until: Instant,
    val orderCount: Long,
    val digest: String,
)
