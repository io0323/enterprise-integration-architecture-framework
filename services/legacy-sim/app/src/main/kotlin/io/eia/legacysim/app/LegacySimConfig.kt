package io.eia.legacysim.app

import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * legacy-sim の設定(環境変数)。パスワードは名前([SecretName])だけを持ち、`SecretProvider` から読む。
 *
 * | 環境変数 | 既定 | 使うコマンド |
 * |---|---|---|
 * | `LEGACY_SIM_DB_URL` | なし(必須) | migrate・simulate |
 * | `LEGACY_SIM_DB_USER` / `LEGACY_SIM_DB_PASSWORD`(または `_FILE`) | `legacy_sim` / なし | migrate だけ(所有者。DBA の作業も行う) |
 * | `LEGACY_SIM_APP_DB_USER` / `LEGACY_SIM_APP_DB_PASSWORD`(または `_FILE`) | `legacy_sim_app` / なし | simulate だけ(レガシーのアプリ) |
 * | `LEGACY_SIM_CDC_DB_USER` | `debezium` | migrate(受注表の SELECT と signal 表の権限を付ける Debezium のロール) |
 */
internal data class LegacySimConfig(
    val dbUrl: String,
    val ownerUser: String,
    val appUser: String,
    val cdcUser: String,
) {
    companion object {
        const val DB_URL = "LEGACY_SIM_DB_URL"
        val OWNER_PASSWORD = SecretName("LEGACY_SIM_DB_PASSWORD")
        val APP_PASSWORD = SecretName("LEGACY_SIM_APP_DB_PASSWORD")
        private const val OWNER_USER = "LEGACY_SIM_DB_USER"
        private const val APP_USER = "LEGACY_SIM_APP_DB_USER"
        private const val CDC_USER = "LEGACY_SIM_CDC_DB_USER"
        private val ROLE_DEFAULTS = mapOf(OWNER_USER to "legacy_sim", APP_USER to "legacy_sim_app", CDC_USER to "debezium")
        private val ROLE_NAME = Regex("^[a-z_][a-z0-9_]{0,62}$")

        fun fromEnvironment(env: Map<String, String>): Result<LegacySimConfig, ValidationError> {
            val url = env[DB_URL].orEmpty()
            val roles = ROLE_DEFAULTS.mapValues { (name, default) -> env[name] ?: default }
            // ロール名は GRANT の SQL に埋め込むため、識別子として安全な文字だけを許す
            val unsafeRole = roles.entries.firstOrNull { !ROLE_NAME.matches(it.value) }
            return when {
                url.isBlank() -> err(ValidationError.of(DB_URL, "必須です"))
                !url.startsWith("jdbc:postgresql://") -> err(ValidationError.of(DB_URL, "jdbc:postgresql:// で始まる URL にしてください"))
                unsafeRole != null -> err(ValidationError.of(unsafeRole.key, "英小文字・数字・_ の 63 文字以内にしてください"))
                else -> ok(LegacySimConfig(url, roles.getValue(OWNER_USER), roles.getValue(APP_USER), roles.getValue(CDC_USER)))
            }
        }
    }
}
