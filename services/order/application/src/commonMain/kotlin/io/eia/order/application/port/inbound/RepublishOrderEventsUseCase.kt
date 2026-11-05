package io.eia.order.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/**
 * 注文の表からイベントを作り直して、もう一度発行する(ADR-0046)。スロットの失効(#80)と Kafka の保持期間の超過(#116)の回復に使う。
 *
 * 作り直せるのは、作成のイベントと、今の状態への遷移(今の版)と、取消だけ。途中の遷移は表に残っていないので戻らない。
 * イベントの ID(`ce_id`)は新しくなるので、受信側は業務のキーで重複を除く。
 */
public interface RepublishOrderEventsUseCase {
    public suspend operator fun invoke(command: RepublishOrderEventsCommand): Result<RepublishReport, DomainError>
}

/**
 * @property from 注文の時刻の始まり(含む)
 * @property to 注文の時刻の終わり(含まない)
 * @property reason 理由(目印のヘッダと監査に入れる。例 `slot-lost`・`retention-exceeded`)
 * @property runId 実行の ID(目印のヘッダと監査に入れる)
 * @property execute false なら件数を数えるだけで、何も書かない
 */
public data class RepublishOrderEventsCommand(
    val from: Instant,
    val to: Instant,
    val reason: String,
    val runId: String,
    val execute: Boolean,
    val requestedBy: RequestedBy,
)

/** @property orders 対象(dry-run)または再発行した(実行)注文の数 */
public data class RepublishReport(
    val orders: Long,
    val executed: Boolean,
)
