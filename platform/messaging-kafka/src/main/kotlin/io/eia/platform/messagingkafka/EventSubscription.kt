package io.eia.platform.messagingkafka

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/** [EventConsumer] が購読する 1 つのトピックと、その処理(ADR-0028 §1)。 */
public sealed interface Subscription {
    /** 購読するトピックの名前(DLQ は購読しない)。 */
    public val topicName: String

    /** 連携 ID(ログの `integration_id`。カタログの ID)。 */
    public val integrationId: String
}

/**
 * 社内のイベント・コマンド(CloudEvents のヘッダ付き。INTEGRATION_STANDARDS §2)の購読。
 *
 * @property topic 購読するトピック(Event・Command のどちらも)
 * @property deserializer 値を読む型(契約の record 名の `@SerialName`。ADR-0025 §1)
 * @property handler 1 件の処理。業務の更新と冪等消費の記録(platform/inbox)を同じトランザクションで行う
 */
public class EventSubscription<T>(
    public val topic: EventTopic,
    public val deserializer: AvroEventDeserializer<T>,
    override val integrationId: String,
    public val handler: EventHandler<T>,
) : Subscription {
    override val topicName: String get() = topic.name

    init {
        require(integrationId.isNotBlank()) { "integrationId が空です" }
    }
}

/**
 * CloudEvents のヘッダを持たない外部のトピックの購読(ADR-0028 改訂履歴)。例: レガシーの生の CDC(Debezium の Envelope。ADR-0026)。
 *
 * - ヘッダを検査しない。レコードごとに新しいトレースと Correlation ID を始める(外部はトレースを持たない)。
 * - 値がない(tombstone)・Avro として読めない値は、リトライせずに DLQ に送る。ほかの失敗の扱い・コミット・読み直しは [EventSubscription] と同じ。
 * - 冪等消費の記録(ce_id)はない。処理の側で、重複しても結果が同じになるようにする(状態の最新を出す変換など)。
 *
 * @property topicName 購読するトピック(命名規約 `{domain}.{entity}.{event}.v{n}` の外。例 `_cdc.legacy.public.t_juchu`)
 * @property deserializer 値を読む型(Apicurio の wire format の Avro)
 * @property handler 1 件の処理
 */
public class ExternalSubscription<T>(
    override val topicName: String,
    public val deserializer: AvroEventDeserializer<T>,
    override val integrationId: String,
    public val handler: RecordHandler<T>,
) : Subscription {
    init {
        require(topicName.isNotBlank()) { "topicName が空です" }
        require(!topicName.endsWith(DeadLetterPublisher.SUFFIX)) { "DLQ は購読しません: $topicName" }
        require(integrationId.isNotBlank()) { "integrationId が空です" }
    }
}

/** 外部のトピックの 1 件の処理。成功なら [Handled]、失敗なら [HandlingFailure](扱いは [EventHandler] と同じ)。 */
public fun interface RecordHandler<T> {
    public suspend fun handle(record: ConsumedRecord<T>): Result<Handled, HandlingFailure>
}

/**
 * 外部のトピックから受信したレコード。値の読み取りは済んでいる。
 *
 * @property key Kafka のキー(外部の形式のまま)。ない場合は null
 */
public class ConsumedRecord<T>(
    public val key: ByteArray?,
    public val value: T,
    public val topic: String,
    public val partition: Int,
    public val offset: Long,
) {
    // 値はログに出さない(CLAUDE.md §5 可観測性)
    override fun toString(): String = "ConsumedRecord(topic=$topic, partition=$partition, offset=$offset)"
}

/** 1 件の処理。成功なら [Handled]、失敗なら [HandlingFailure](種類でリトライ・DLQ・読み直しを決める)。 */
public fun interface EventHandler<T> {
    public suspend fun handle(event: ConsumedEvent<T>): Result<Handled, HandlingFailure>
}

/**
 * 受信したイベント。ヘッダ(CloudEvents)の検査と値の読み取りは済んでいる。
 *
 * @property key Kafka のキー(UTF-8。パーティションキー。Saga では Saga ID)。ない場合は null
 */
public class ConsumedEvent<T>(
    public val metadata: EventMetadata,
    public val key: String?,
    public val value: T,
    public val topic: String,
    public val partition: Int,
    public val offset: Long,
) {
    // 値はログに出さない(CLAUDE.md §5 可観測性)
    override fun toString(): String = "ConsumedEvent(topic=$topic, partition=$partition, offset=$offset, id=${metadata.id})"
}

/** 処理の結果(メトリクスの `outcome`)。どちらもオフセットを進める。 */
public enum class Handled {
    /** 業務の処理をした。 */
    PROCESSED,

    /** 処理済み(processed_message・業務キーの冪等)のため、何もしなかった。 */
    DUPLICATE,
}

/**
 * 処理の失敗。種類で扱いを分ける(ADR-0028 §2。Framework 6.5・13.2)。
 *
 * | 種類 | 例 | 扱い |
 * |---|---|---|
 * | [Rejected] | 契約違反・業務の検証の失敗(4xx 相当) | リトライせずに DLQ(本流を止めない) |
 * | [Transient] | 楽観的ロックの衝突・直列化の失敗など、そのメッセージの処理だけの一時的な失敗 | その場でリトライ(既定 3 回。Backoff + Jitter)し、尽きたら DLQ |
 * | [Unavailable] | 自分の DB・Schema Registry が使えない(全部のメッセージが同じく失敗する) | DLQ に送らず、最後のコミットの位置から読み直し続ける(`ready=false`) |
 *
 * [Unavailable] を DLQ に送らないのは、障害の間に届いたメッセージがすべて DLQ に入り、回復の後に大量の Replay が要るため。
 * その間は lag が増え、lag のアラートが拾う。
 *
 * @property code 原因の種類。DLQ の `eiaf.dlq.reason` とメトリクスのラベルに使うので、種類の数は有限にする
 * @property detail 項目の名前と破った規則(**値は入れない**。DLQ の `eiaf.dlq.detail`)
 */
public sealed interface HandlingFailure {
    public val code: String
    public val detail: String

    public data class Rejected(
        override val code: String,
        override val detail: String,
    ) : HandlingFailure

    public data class Transient(
        override val code: String,
        override val detail: String,
    ) : HandlingFailure

    public data class Unavailable(
        override val code: String,
        override val detail: String,
    ) : HandlingFailure

    public companion object {
        /**
         * [error] を、NonRetryable なら [Rejected]、Retryable なら [Transient] にする。
         * 自分の DB が使えないなど、全部のメッセージが同じく失敗する Retryable は、呼び出し側が [Unavailable] にする。
         */
        public fun of(error: DomainError): HandlingFailure =
            when (error) {
                is DomainError.NonRetryable -> Rejected(error.code, error.message)
                is DomainError.Retryable -> Transient(error.code, error.message)
            }
    }
}
