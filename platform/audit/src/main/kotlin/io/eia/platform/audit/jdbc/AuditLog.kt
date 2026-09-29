package io.eia.platform.audit.jdbc

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.AuditEvent
import io.eia.platform.audit.AuditEventNormalizer
import io.eia.platform.audit.AuditMisuse
import io.eia.platform.audit.AuditRecord
import io.eia.platform.audit.ChainHash
import io.eia.platform.audit.canonical.CanonicalForm
import io.eia.platform.audit.canonical.CanonicalForms
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.ZoneOffset

/**
 * 監査記録を追記する(ADR-0017)。
 *
 * 業務の更新と同じトランザクションで呼ぶ(業務がロールバックすれば記録も残らない。`seq` に欠番ができない)。
 * `pg_advisory_xact_lock` でチェーンへの追記を直列にし、コミットかロールバックでロックが外れる。
 *
 * 前提:
 * - [connection] は自動コミットが無効(トランザクションの中)であること。有効だとロックが文ごとに外れ、チェーンが分岐する。
 * - 分離レベルが READ COMMITTED であること。REPEATABLE READ 以上では、ロックを取る前のスナップショットで末尾を読むため、
 *   直前にコミットされた記録が見えず、同じ `seq` を使って一意制約の違反になる。
 *
 * Exposed のトランザクションからは [appendAudit] を使う。
 */
public class AuditLog(
    private val clock: Clock = Clock.systemUTC(),
    private val canonicalForm: CanonicalForm = CanonicalForms.CURRENT,
) {
    public fun append(
        connection: Connection,
        event: AuditEvent,
    ): Result<AuditRecord, AuditError> {
        val normalized =
            when (val result = AuditEventNormalizer.normalize(event)) {
                is Result.Ok -> result.value
                is Result.Err -> return result
            }
        val recordedAt = AuditEventNormalizer.truncateToMicros(clock.instant())
        return sqlCatching {
            checkTransaction(connection)?.let { return@sqlCatching err(it) }
            connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
                it.setLong(1, CHAIN_LOCK_KEY)
                it.executeQuery().close()
            }
            val tail = AuditLogReader.head(connection)
            val record =
                normalized.toRecord(
                    seq = (tail?.seq ?: 0L) + 1,
                    prevHash = tail?.hash ?: ChainHash.GENESIS.hex,
                    recordedAt = recordedAt,
                )
            val hashed = record.copy(hash = canonicalForm.hash(record).hex)
            insert(connection, hashed)
            ok(hashed)
        }
    }

    private fun checkTransaction(connection: Connection): AuditMisuse? {
        if (connection.autoCommit) return AuditMisuse("監査記録はトランザクションの中で追記してください(自動コミットが有効です)")
        val isolation =
            connection.prepareStatement("SELECT current_setting('transaction_isolation')").use { statement ->
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
        return if (isolation == READ_COMMITTED) null else AuditMisuse("監査記録の追記は READ COMMITTED で行ってください(現在: $isolation)")
    }

    private fun AuditEventNormalizer.Normalized.toRecord(
        seq: Long,
        prevHash: String,
        recordedAt: java.time.Instant,
    ): AuditRecord =
        AuditRecord(
            seq = seq,
            canonicalVersion = canonicalForm.version,
            occurredAt = occurredAt,
            recordedAt = recordedAt,
            actorType = actorType,
            actorId = actorId,
            action = action,
            targetType = targetType,
            targetId = targetId,
            destination = destination,
            outcome = outcome,
            payloadSha256 = payloadSha256,
            payloadRef = payloadRef,
            correlationId = correlationId,
            traceparent = traceparent,
            details = details,
            prevHash = prevHash,
            hash = "",
        )

    private fun insert(
        connection: Connection,
        record: AuditRecord,
    ) {
        connection.prepareStatement(INSERT).use { statement ->
            var index = 0
            statement.setLong(++index, record.seq)
            statement.setInt(++index, record.canonicalVersion)
            statement.setObject(++index, record.occurredAt.atOffset(ZoneOffset.UTC))
            statement.setObject(++index, record.recordedAt.atOffset(ZoneOffset.UTC))
            listOf(
                record.actorType,
                record.actorId,
                record.action,
                record.targetType,
                record.targetId,
                record.destination,
                record.outcome,
                record.payloadSha256,
                record.payloadRef,
                record.correlationId,
                record.traceparent,
            ).forEach { statement.setString(++index, it) }
            statement.setString(++index, JsonObject(record.details.mapValues { JsonPrimitive(it.value) }).toString())
            statement.setString(++index, record.prevHash)
            statement.setString(++index, record.hash)
            statement.executeUpdate()
        }
    }

    public companion object {
        /** チェーンへの追記を直列にする advisory lock のキー(ASCII の "EIAFAUDI")。DB ごとに 1 本のチェーンなので定数でよい。 */
        public const val CHAIN_LOCK_KEY: Long = 0x4549_4146_4155_4449L
        private const val READ_COMMITTED = "read committed"

        private val INSERT =
            """
            INSERT INTO ${AuditSchema.TABLE} (
                seq, canonical_version, occurred_at, recorded_at, actor_type, actor_id, action, target_type, target_id,
                destination, outcome, payload_sha256, payload_ref, correlation_id, traceparent, details, prev_hash, hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent()
    }
}

/** SQL の例外を [AuditError] に変換する(接続の失敗は Retryable、権限・制約の違反などは NonRetryable)。 */
internal inline fun <T> sqlCatching(block: () -> Result<T, AuditError>): Result<T, AuditError> =
    try {
        block()
    } catch (e: SQLException) {
        err(SqlErrors.classify(e))
    }
