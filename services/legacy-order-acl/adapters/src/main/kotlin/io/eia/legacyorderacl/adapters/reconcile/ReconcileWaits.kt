package io.eia.legacyorderacl.adapters.reconcile

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 照合の待ち(CDC の取り込み・ACL の処理)。[timeout] を超えたら、ずれではなく検査の失敗にする(ADR-0027)。
 *
 * @property poll 待つ間に確かめる間隔
 */
public data class ReconcileWaits(
    val timeout: Duration = 2.minutes,
    val poll: Duration = 1.seconds,
)
