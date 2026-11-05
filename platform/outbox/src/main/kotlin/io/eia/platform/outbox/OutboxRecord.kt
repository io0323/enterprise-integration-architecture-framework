package io.eia.platform.outbox

import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.EventTopic

/**
 * Outbox の表の 1 行(ADR-0007 §1)。
 *
 * | 列 | 値 |
 * |---|---|
 * | `id`・`ce_id` | [metadata] の `id`(UUIDv7。両方に同じ値を入れる。ADR-0007 の改訂履歴 2026-10-07) |
 * | `topic` | [topic](Debezium の EventRouter が、この値をそのままトピック名にする) |
 * | `aggregate_type`・`aggregate_id` | [aggregateType]・[aggregateId](`aggregate_id` は Kafka のキー = パーティションキー) |
 * | `event_type` | [metadata] の `type`(`ce_type`) |
 * | `payload` | [payload](`AvroEventSerializer` が作った、スキーマ ID 付きの Avro。ADR-0025 §1) |
 * | `traceparent`・`correlation_id`・`ce_source`・`ce_time` | [metadata] の同名の値 |
 * | `republished`・`republish_reason`・`republish_run_id` | [republish](再発行のときだけ。ADR-0046) |
 *
 * @property aggregateType 集約の種類(例 `order`)
 * @property aggregateId 集約の ID(例 注文 ID)。同じ集約のイベントは同じパーティションに入り、順序が保たれる
 */
public class OutboxRecord(
    public val topic: EventTopic,
    public val aggregateType: String,
    public val aggregateId: String,
    public val metadata: EventMetadata,
    public val payload: ByteArray,
    public val republish: OutboxRepublish? = null,
) {
    init {
        require(aggregateType.isNotBlank()) { "aggregateType が空です" }
        require(aggregateId.isNotBlank()) { "aggregateId が空です" }
        require(metadata.type == topic.ceType) { "ce_type(${metadata.type})がトピック $topic と合いません" }
        require(payload.isNotEmpty()) { "payload が空です" }
    }

    /** 再発行の目印を付けた記録。 */
    public fun republished(mark: OutboxRepublish): OutboxRecord = OutboxRecord(topic, aggregateType, aggregateId, metadata, payload, mark)

    // ペイロードの値はログに出さない(CLAUDE.md §5 可観測性)
    override fun toString(): String =
        "OutboxRecord(topic=$topic, aggregateType=$aggregateType, aggregateId=$aggregateId, " +
            "id=${metadata.id}, payload=${payload.size} bytes)"
}

/**
 * 再発行の目印(ADR-0046)。発行するコネクタがヘッダ(`eiaf.republished`・`eiaf.republish.reason`・`eiaf.republish.run-id`)にする。
 *
 * @property reason 理由。業務のデータを入れない
 * @property runId 実行の ID
 */
public data class OutboxRepublish(
    val reason: String,
    val runId: String,
) {
    init {
        require(reason.isNotBlank()) { "reason が空です" }
        require(runId.isNotBlank()) { "runId が空です" }
    }
}
