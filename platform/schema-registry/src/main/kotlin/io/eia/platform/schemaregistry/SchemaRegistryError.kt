package io.eia.platform.schemaregistry

import io.eia.shared.kernel.DomainError

/**
 * Schema Registry の操作の失敗。メッセージにスキーマの全文は入れない。
 *
 * 各実装は [DomainError.Retryable] か [DomainError.NonRetryable] のどちらか一方を実装する
 * ([DomainError] は kernel の sealed interface のため、ここから直接は継承できない。ADR-0011)。
 */
public sealed interface SchemaRegistryError {
    public val code: String
    public val message: String

    /** `Result` の Err に入れる形(各実装は Retryable か NonRetryable のどちらかの [DomainError])。 */
    public fun asDomainError(): DomainError =
        when (this) {
            is SchemaRegistryUnavailable -> this
            is SchemaNotRegistered -> this
            is SchemaRejected -> this
            is SchemaContentNotFound -> this
            is InvalidRegistryResponse -> this
            is SchemaIdsNotResolved -> this
        }
}

/**
 * レジストリに接続できない・タイムアウト・5xx など、一時的な失敗。
 *
 * @param reason `timeout` / `connection` / `server_error` など
 */
public data class SchemaRegistryUnavailable(
    public val reason: String,
    public val status: Int? = null,
) : SchemaRegistryError,
    DomainError.Retryable {
    override val code: String get() = "schema_registry_unavailable"
    override val message: String get() = "Schema Registry が使えません($reason${status?.let { ", status=$it" }.orEmpty()})"
}

/** スキーマが登録されていない(`make schemas` の実行漏れ、または契約と実装のスキーマの食い違い)。 */
public data class SchemaNotRegistered(
    public val artifactId: String,
) : SchemaRegistryError,
    DomainError.NonRetryable {
    override val code: String get() = "schema_not_registered"
    override val message: String get() = "スキーマが登録されていません(artifactId=$artifactId)。make schemas で契約を登録してください"
}

/**
 * レジストリが登録を拒否した(互換性ルール FULL_TRANSITIVE・妥当性ルールの違反など。ADR-0014)。
 *
 * @param name 拒否の種類(Apicurio の `name`。例 `RuleViolationException`)
 * @param detail レジストリの説明(違反した項目のパスなど)
 */
public data class SchemaRejected(
    public val artifactId: String,
    public val status: Int,
    public val name: String?,
    public val detail: String?,
) : SchemaRegistryError,
    DomainError.NonRetryable {
    override val code: String get() = "schema_rejected"
    override val message: String
        get() =
            "スキーマの登録を拒否されました(artifactId=$artifactId, status=$status${name?.let { ", $it" }.orEmpty()})" +
                detail?.let { ": $it" }.orEmpty()
}

/** 指定した contentId のスキーマがない。 */
public data class SchemaContentNotFound(
    public val contentId: ContentId,
) : SchemaRegistryError,
    DomainError.NonRetryable {
    override val code: String get() = "schema_content_not_found"
    override val message: String get() = "contentId=$contentId のスキーマがありません"
}

/** 応答の形式が想定と違う(API の版の食い違いなど)。 */
public data class InvalidRegistryResponse(
    public val reason: String,
) : SchemaRegistryError,
    DomainError.NonRetryable {
    override val code: String get() = "invalid_registry_response"
    override val message: String get() = "Schema Registry の応答が不正です($reason)"
}

/** 起動時の解決([SchemaIdBook.resolve])が終わっていない。終わるまで /health/ready を失敗にするので、通常は起きない。 */
public data class SchemaIdsNotResolved(
    public val topic: String,
) : SchemaRegistryError,
    DomainError.Retryable {
    override val code: String get() = "schema_ids_not_resolved"
    override val message: String get() = "$topic のスキーマ ID がまだ解決されていません"
}
