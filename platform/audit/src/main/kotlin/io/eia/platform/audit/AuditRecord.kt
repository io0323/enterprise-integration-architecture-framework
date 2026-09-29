package io.eia.platform.audit

import java.time.Instant

/**
 * テーブルに保存された 1 件の記録。検証のため、列の値を型に解釈せず保存されたとおりに持つ
 * (改竄された値も、そのまま直列化してハッシュを計算し直せるように)。
 *
 * NULL は null、空文字列は `""` で、直列化でも区別する(ADR-0017 の直列化の仕様)。
 */
public data class AuditRecord(
    val seq: Long,
    val canonicalVersion: Int,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val actorType: String?,
    val actorId: String?,
    val action: String?,
    val targetType: String?,
    val targetId: String?,
    val destination: String?,
    val outcome: String?,
    val payloadSha256: String?,
    val payloadRef: String?,
    val correlationId: String?,
    val traceparent: String?,
    val details: Map<String, String?>,
    val prevHash: String,
    val hash: String,
)

/** テーブルから読んだ 1 行。列の値を [AuditRecord] として解釈できなかった行は [Malformed]。 */
public sealed interface StoredRow {
    public val seq: Long
    public val hash: String
    public val prevHash: String

    public data class Parsed(
        val record: AuditRecord,
    ) : StoredRow {
        override val seq: Long get() = record.seq
        override val hash: String get() = record.hash
        override val prevHash: String get() = record.prevHash
    }

    /** 例: details が JSON のオブジェクトでない・値が文字列でも null でもない。改竄の疑いとして報告する。 */
    public data class Malformed(
        override val seq: Long,
        override val hash: String,
        override val prevHash: String,
        val reason: String,
    ) : StoredRow
}
