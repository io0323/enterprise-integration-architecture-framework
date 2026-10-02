package io.eia.platform.schemaregistry

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Schema Registry(Apicurio Registry 3)への接続の設定。
 *
 * @property baseUrl REST API のベース URL(例 `http://apicurio:8080/apis/registry/v3`)
 * @property groupId アーティファクトのグループ。公式の Serde の既定(`default`)に揃える(ADR-0025 §2)
 * @property requestTimeout 1 回の要求(接続から本文の読み取りまで)の上限
 */
public data class SchemaRegistryConfig(
    public val baseUrl: String,
    public val groupId: String = DEFAULT_GROUP_ID,
    public val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
) {
    init {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) { "baseUrl は http(s) の URL にしてください: $baseUrl" }
        require(groupId.isNotBlank()) { "groupId が空です" }
        require(requestTimeout.isPositive()) { "requestTimeout は正の値にしてください" }
    }

    public companion object {
        public const val DEFAULT_GROUP_ID: String = "default"
        public val DEFAULT_REQUEST_TIMEOUT: Duration = 5.seconds
    }
}
