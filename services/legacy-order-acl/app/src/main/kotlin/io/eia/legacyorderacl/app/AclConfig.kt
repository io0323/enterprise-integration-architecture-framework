package io.eia.legacyorderacl.app

import io.eia.legacyorderacl.adapters.inbound.LegacyChangeHandler
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * legacy-order-acl の設定(環境変数)。Kafka と Apicurio はローカルでは未認証(ADR-0008 の縮退・#26・#29)。
 *
 * | 環境変数 | 既定 |
 * |---|---|
 * | `LEGACY_ORDER_ACL_KAFKA_BOOTSTRAP` | なし(必須。例 `kafka:9092`) |
 * | `LEGACY_ORDER_ACL_SCHEMA_REGISTRY_URL` | なし(必須。例 `http://apicurio:8080/apis/registry/v3`) |
 * | `LEGACY_ORDER_ACL_GROUP_ID` | `legacy-order-acl.translate` |
 * | `LEGACY_ORDER_ACL_HEALTH_PORT` | 8081(`/health/live` と `/health/ready` だけ。平文。コンテナの外に公開しない) |
 * | `LEGACY_ORDER_ACL_RECONCILE_*` | 照合の設定([ReconcileConfig])。レガシーの DB のパスワードだけは秘密情報(`LEGACY_RECONCILE_DB_PASSWORD`) |
 */
internal data class AclConfig(
    val bootstrapServers: String,
    val schemaRegistryUrl: String,
    val groupId: String,
    val healthPort: Int,
    val reconcile: ReconcileConfig? = null,
) {
    companion object {
        const val KAFKA_BOOTSTRAP = "LEGACY_ORDER_ACL_KAFKA_BOOTSTRAP"
        const val SCHEMA_REGISTRY_URL = "LEGACY_ORDER_ACL_SCHEMA_REGISTRY_URL"
        const val GROUP_ID = "LEGACY_ORDER_ACL_GROUP_ID"
        const val HEALTH_PORT = "LEGACY_ORDER_ACL_HEALTH_PORT"
        private const val DEFAULT_HEALTH_PORT = 8081
        private const val MAX_PORT = 65_535

        fun fromEnvironment(env: Map<String, String>): Result<AclConfig, ValidationError> {
            val violations = mutableListOf<FieldViolation>()

            fun required(name: String): String =
                env[name]?.takeIf { it.isNotBlank() } ?: "".also { violations += FieldViolation(name, "必須です") }
            val bootstrap = required(KAFKA_BOOTSTRAP)
            val registry = required(SCHEMA_REGISTRY_URL)
            val port =
                env[HEALTH_PORT]?.let {
                    it.toIntOrNull()?.takeIf { p -> p in 0..MAX_PORT }
                        ?: (-1).also { violations += FieldViolation(HEALTH_PORT, "0〜65535 のポート番号にしてください") }
                }
            val reconcile =
                when (val parsed = ReconcileConfig.fromEnvironment(env)) {
                    is Result.Ok -> parsed.value
                    is Result.Err -> null.also { violations += parsed.error.violations }
                }
            return if (violations.isEmpty()) {
                ok(AclConfig(bootstrap, registry, env[GROUP_ID] ?: LegacyChangeHandler.GROUP_ID, port ?: DEFAULT_HEALTH_PORT, reconcile))
            } else {
                err(ValidationError(violations))
            }
        }
    }
}
