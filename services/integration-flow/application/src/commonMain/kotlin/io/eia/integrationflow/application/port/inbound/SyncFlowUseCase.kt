package io.eia.integrationflow.application.port.inbound

import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.Trigger
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * Flow の poll の trigger の 1 回の同期(Webhook の取りこぼしの補い。ADR-0035 §6)。前の同期の Watermark の後に変わったレコードを
 * ページングで読み、Webhook の経路と同じ新旧の記録で判定して、新しい版だけを出す。すべてのレコードを処理できたときだけ Watermark を進める。
 * Connector の失敗(429 を再試行し尽くした・接続できない)は Err(Watermark は進めない。次の回にやり直す)。
 */
public fun interface SyncFlowUseCase {
    public suspend operator fun invoke(
        flow: FlowDefinition,
        trigger: Trigger.Poll,
    ): Result<SyncReport, DomainError>
}

/**
 * @property failed 写せない・Canonical の検証を通らなかったレコードの数(1 つでもあれば Watermark を進めない)
 */
public data class SyncReport(
    val pages: Int,
    val published: Int,
    val same: Int,
    val stale: Int,
    val failed: Int,
)
