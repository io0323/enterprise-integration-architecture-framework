package io.eia.b2bgateway.adapters.persistence

import io.eia.b2bgateway.application.port.outbound.AcknowledgementKind
import io.eia.b2bgateway.application.port.outbound.Acknowledgements
import io.eia.b2bgateway.application.port.outbound.PendingAcknowledgement
import io.eia.b2bgateway.application.port.outbound.StoredOriginal
import io.eia.platform.audit.Actor
import io.eia.platform.audit.ActorType
import io.eia.platform.audit.AuditEvent
import io.eia.platform.audit.AuditOutcome
import io.eia.platform.audit.AuditTarget
import io.eia.platform.audit.ChainHash
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.observability.context.CurrentTrace
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * 取引先に送る応答の記録(表 `b2b.acknowledgement`。ADR-0040 §7)。送った印と監査(`b2b.ack.send`。ADR-0017)を 1 つのトランザクションで書く。
 *
 * 監査の記録の項目
 * | 項目 | 値 |
 * |---|---|
 * | 誰が | `service` / `b2b-gateway` |
 * | 何を | `b2b.ack.send` / `b2b_acknowledgement` / 応答の記録の ID / `success` |
 * | どこへ | `partner:{取引先の ID}` |
 * | 本文の代わり | 送った原本(取引先あての暗号文)の SHA-256 と、原本の場所 |
 * | 補足 | 種類(contrl・aperak)・受け取りの記録の ID・取引先の側の正式名・取引先の側にすでにあったか |
 *
 * 2 つの処理が同じ応答を送ったときは、先に印を付けたほうだけが監査を書く(取引先の側の正式名は同じなので、後の処理は
 * [Delivery.ALREADY_PRESENT][io.eia.b2bgateway.application.port.outbound.Delivery.ALREADY_PRESENT] になり、二重には届かない)。
 */
public class JdbcAcknowledgements(
    private val dataSource: DataSource,
    private val log: AuditLog,
    private val clock: Clock = Clock.systemUTC(),
) : Acknowledgements {
    override suspend fun pending(
        partnerId: String,
        limit: Int,
    ): Result<List<PendingAcknowledgement>, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(PENDING).use { statement ->
                statement.setString(1, partnerId)
                statement.setInt(2, limit)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                PendingAcknowledgement(
                                    id = rs.getString("id"),
                                    documentId = rs.getString("document_id"),
                                    partnerId = rs.getString("partner_id"),
                                    kind = AcknowledgementKind.valueOf(rs.getString("kind")),
                                    original = StoredOriginal(rs.getString("original_ref"), rs.getString("original_sha256")),
                                ),
                            )
                        }
                    }
                }
            }
        }

    override suspend fun sent(
        acknowledgement: PendingAcknowledgement,
        remoteName: String,
        alreadyPresent: Boolean,
    ): Result<Unit, DomainError> {
        val trace = CurrentTrace.get()
        return dataSource.inTransaction { connection ->
            val marked =
                connection.prepareStatement(MARK_SENT).use { statement ->
                    var index = 0
                    statement.setString(++index, remoteName)
                    statement.setBoolean(++index, alreadyPresent)
                    statement.setObject(++index, UUID.fromString(acknowledgement.id))
                    statement.executeUpdate()
                }
            if (marked == 1) {
                val event = auditEvent(acknowledgement, remoteName, alreadyPresent, trace)
                when (val appended = log.append(connection, event)) {
                    is Result.Ok -> Unit

                    // 追記できなければ、印も付けない(次の回に送り直す。取引先の側にあれば ALREADY_PRESENT になる。ADR-0017 §4)
                    is Result.Err -> throw AuditFailed(appended.error.code, appended.error is DomainError.Retryable)
                }
            }
        }
    }

    private fun auditEvent(
        acknowledgement: PendingAcknowledgement,
        remoteName: String,
        alreadyPresent: Boolean,
        trace: CurrentTrace,
    ): AuditEvent =
        AuditEvent(
            occurredAt = clock.instant(),
            actor = Actor(ActorType.SERVICE, SERVICE),
            action = ACTION,
            target = AuditTarget(TARGET_TYPE, acknowledgement.id),
            outcome = AuditOutcome.SUCCESS,
            destination = "partner:${acknowledgement.partnerId}",
            payloadSha256 = (ChainHash.parse(acknowledgement.original.sha256) as Result.Ok).value,
            payloadRef = acknowledgement.original.ref,
            correlationId = trace.correlationId,
            traceparent = trace.traceParent,
            details =
                mapOf(
                    "kind" to acknowledgement.kind.name.lowercase(),
                    "document_id" to acknowledgement.documentId,
                    "remote_name" to remoteName,
                    "already_present" to alreadyPresent.toString(),
                ),
        )

    private companion object {
        const val ACTION = "b2b.ack.send"
        const val TARGET_TYPE = "b2b_acknowledgement"
        const val SERVICE = "b2b-gateway"

        const val PENDING = """
            SELECT id::text, document_id::text, partner_id, kind, original_ref, original_sha256 FROM b2b.acknowledgement
            WHERE state = 'PENDING' AND partner_id = ? ORDER BY created_at, id LIMIT ?
        """
        const val MARK_SENT = """
            UPDATE b2b.acknowledgement SET state = 'SENT', sent_at = clock_timestamp(), remote_name = ?, already_present = ?
            WHERE id = ? AND state = 'PENDING'
        """
    }
}
