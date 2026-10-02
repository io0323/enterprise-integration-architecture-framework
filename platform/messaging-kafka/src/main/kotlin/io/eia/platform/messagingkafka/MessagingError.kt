package io.eia.platform.messagingkafka

import io.eia.platform.schemaregistry.SchemaRegistryError
import io.eia.shared.kernel.DomainError

/**
 * イベントの送受信の失敗。メッセージにペイロードの値を入れない(CLAUDE.md §5 可観測性: ペイロード全文のログ禁止)。
 *
 * 各実装は [DomainError.Retryable] か [DomainError.NonRetryable] のどちらか一方を実装する
 * ([DomainError] は kernel の sealed interface のため、ここから直接は継承できない。ADR-0011)。
 */
public sealed interface MessagingError {
    public val code: String
    public val message: String

    /** `Result` の Err に入れる形(各実装は Retryable か NonRetryable のどちらかの [DomainError])。 */
    public fun asDomainError(): DomainError =
        when (this) {
            is EventEncodingFailed -> this
            is MalformedEventPayload -> this
            is SchemaUnavailable.Temporary -> this
            is SchemaUnavailable.Permanent -> this
            is PublishFailed.Retryable -> this
            is PublishFailed.NonRetryable -> this
        }
}

/** 値をスキーマに合わせてエンコードできない(必須の項目の欠落・型の不一致など)。実装と契約の食い違いなので、リトライしない。 */
public data class EventEncodingFailed(
    public val topic: String,
    public val reason: String,
) : MessagingError,
    DomainError.NonRetryable {
    override val code: String get() = "event_encoding_failed"
    override val message: String get() = "$topic のイベントをエンコードできません($reason)"
}

/** ペイロードを読めない(wire format の不正・書き手のスキーマと合わない)。受信側では DLQ に送る(P07)。 */
public data class MalformedEventPayload(
    public val reason: String,
) : MessagingError,
    DomainError.NonRetryable {
    override val code: String get() = "malformed_event_payload"
    override val message: String get() = "イベントのペイロードを読めません($reason)"
}

/** スキーマの ID・内容を得られない。[cause] の分類(一時的かどうか)で実装を分ける。 */
public sealed interface SchemaUnavailable : MessagingError {
    public val cause: SchemaRegistryError

    public data class Temporary(
        override val cause: SchemaRegistryError,
    ) : SchemaUnavailable,
        DomainError.Retryable {
        override val code: String get() = cause.code
        override val message: String get() = cause.message
    }

    public data class Permanent(
        override val cause: SchemaRegistryError,
    ) : SchemaUnavailable,
        DomainError.NonRetryable {
        override val code: String get() = cause.code
        override val message: String get() = cause.message
    }

    public companion object {
        public fun of(cause: SchemaRegistryError): SchemaUnavailable =
            if (cause is DomainError.Retryable) Temporary(cause) else Permanent(cause)
    }
}

/**
 * Kafka への送信の失敗。Kafka の `RetriableException`(リーダーの交代・タイムアウトなど)は [Retryable]、
 * それ以外(認可・レコードが大きすぎるなど)は [NonRetryable]。
 *
 * @param reason 例外の型の名前(メッセージはレコードの内容を含みうるため入れない)
 */
public sealed interface PublishFailed : MessagingError {
    public val topic: String
    public val reason: String

    public data class Retryable(
        override val topic: String,
        override val reason: String,
    ) : PublishFailed,
        DomainError.Retryable {
        override val code: String get() = "publish_failed"
        override val message: String get() = "$topic への送信に失敗しました($reason)"
    }

    public data class NonRetryable(
        override val topic: String,
        override val reason: String,
    ) : PublishFailed,
        DomainError.NonRetryable {
        override val code: String get() = "publish_failed"
        override val message: String get() = "$topic への送信に失敗しました($reason)"
    }
}
