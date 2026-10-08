package io.eia.legacyorderacl.app

import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 照合(reconcile。ADR-0027)の設定。`LEGACY_ORDER_ACL_RECONCILE_DB_URL` がなければ照合をしない。
 *
 * | 環境変数 | 既定 |
 * |---|---|
 * | `LEGACY_ORDER_ACL_RECONCILE_DB_URL` | なし(照合をしない。例 `jdbc:postgresql://postgres:5432/legacy_sim`。本番はレプリカ) |
 * | `LEGACY_ORDER_ACL_RECONCILE_SLOT_DB_URL` | `…_RECONCILE_DB_URL` と同じ(レプリケーションスロットの位置を読む接続先。スロットはプライマリにだけある) |
 * | `LEGACY_ORDER_ACL_RECONCILE_DB_USER` / `LEGACY_RECONCILE_DB_PASSWORD`(または `_FILE`) | `eiaf_reconcile` / なし |
 * | `LEGACY_ORDER_ACL_RECONCILE_INTERVAL` | 15m(業務の負荷を見て決める) |
 * | `LEGACY_ORDER_ACL_RECONCILE_RECHECK_AFTER` | 30s(食い違ったキーを比べ直すまでの待ち) |
 * | `LEGACY_ORDER_ACL_RECONCILE_WAIT_TIMEOUT` | 2m(CDC の取り込み・ACL の処理を待つ上限。超えたら検査の失敗) |
 *
 * 期間は ISO 8601(`PT15M`)か Kotlin の表記(`15m`)。
 */
internal data class ReconcileConfig(
    val dbUrl: String,
    val slotDbUrl: String,
    val user: String,
    val interval: Duration,
    val recheckAfter: Duration,
    val waitTimeout: Duration,
) {
    companion object {
        const val DB_URL = "LEGACY_ORDER_ACL_RECONCILE_DB_URL"
        const val SLOT_DB_URL = "LEGACY_ORDER_ACL_RECONCILE_SLOT_DB_URL"
        const val DB_USER = "LEGACY_ORDER_ACL_RECONCILE_DB_USER"
        const val INTERVAL = "LEGACY_ORDER_ACL_RECONCILE_INTERVAL"
        const val RECHECK_AFTER = "LEGACY_ORDER_ACL_RECONCILE_RECHECK_AFTER"
        const val WAIT_TIMEOUT = "LEGACY_ORDER_ACL_RECONCILE_WAIT_TIMEOUT"
        val PASSWORD = SecretName("LEGACY_RECONCILE_DB_PASSWORD")

        /** 照合をしない設定なら null。 */
        fun fromEnvironment(env: Map<String, String>): Result<ReconcileConfig?, ValidationError> {
            val url = env[DB_URL]?.takeIf { it.isNotBlank() } ?: return ok(null)
            val violations = mutableListOf<FieldViolation>()

            fun duration(
                name: String,
                default: Duration,
            ): Duration =
                env[name]?.let { raw ->
                    Duration.parseOrNull(raw)?.takeIf { it.isPositive() }
                        ?: default.also { violations += FieldViolation(name, "正の期間にしてください") }
                } ?: default
            val config =
                ReconcileConfig(
                    dbUrl = url,
                    slotDbUrl = env[SLOT_DB_URL]?.takeIf { it.isNotBlank() } ?: url,
                    user = env[DB_USER] ?: "eiaf_reconcile",
                    interval = duration(INTERVAL, 15.minutes),
                    recheckAfter = duration(RECHECK_AFTER, 30.seconds),
                    waitTimeout = duration(WAIT_TIMEOUT, 2.minutes),
                )
            if (!url.startsWith("jdbc:postgresql://")) violations += FieldViolation(DB_URL, "jdbc:postgresql:// で始まる URL にしてください")
            return if (violations.isEmpty()) ok(config) else err(ValidationError(violations))
        }
    }
}
