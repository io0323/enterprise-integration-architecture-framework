package io.eia.platform.audit

import io.eia.platform.audit.canonical.CanonicalFormV1
import java.time.Instant

/** テスト用に、正しくつながったチェーンを作る。 */
internal object TestChains {
    val BASE: Instant = Instant.parse("2026-09-28T00:00:00Z")

    fun record(
        seq: Long,
        prevHash: String = ChainHash.GENESIS.hex,
        details: Map<String, String?> = mapOf("order.status" to "created"),
    ): AuditRecord =
        AuditRecord(
            seq = seq,
            canonicalVersion = CanonicalFormV1.version,
            occurredAt = BASE.plusSeconds(seq),
            recordedAt = BASE.plusSeconds(seq).plusMillis(1),
            actorType = "service",
            actorId = "order-service",
            action = "order.create",
            targetType = "order",
            targetId = "ord-$seq",
            destination = "kafka:sales.order.created.v1",
            outcome = "success",
            payloadSha256 = null,
            payloadRef = "orders/ord-$seq",
            correlationId = "corr-$seq",
            traceparent = null,
            details = details,
            prevHash = prevHash,
            hash = "",
        )

    /** seq 1..[size] の正しいチェーン。 */
    fun chain(size: Int): List<AuditRecord> {
        val records = mutableListOf<AuditRecord>()
        var prev = ChainHash.GENESIS.hex
        for (seq in 1..size) {
            val record = record(seq.toLong(), prev).rehash()
            records += record
            prev = record.hash
        }
        return records
    }

    fun AuditRecord.rehash(): AuditRecord = copy(hash = CanonicalFormV1.hash(this).hex)

    fun List<AuditRecord>.rows(): List<StoredRow> = map { StoredRow.Parsed(it) }
}
