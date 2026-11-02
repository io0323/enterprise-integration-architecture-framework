package io.eia.b2bgateway.adapters.persistence

import io.eia.b2bgateway.adapters.archive.sha256
import io.eia.b2bgateway.adapters.order.SubmissionSnapshot
import io.eia.b2bgateway.application.port.outbound.OrderSubmissions
import io.eia.b2bgateway.application.port.outbound.PendingSubmission
import io.eia.b2bgateway.application.port.outbound.StoredAcknowledgement
import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.ServiceCharacters
import io.eia.b2bgateway.domain.validation.InterchangeHeader
import io.eia.b2bgateway.domain.validation.PartyIdentification
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
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.toKotlinInstant

/**
 * 受け付けた注文の社内への取り込みの送信待ちと結果(表 `b2b.order_submission`。ADR-0040 §6)。
 * 結果(受け付けられた・受け付けられなかった・期限切れ)・業務の応答の送信待ち(`b2b.acknowledgement`)・監査(`b2b.order.submit`)を、
 * 1 つのトランザクションで書く。2 つの処理が同じ注文を送っても、先に結果を書いたほうだけが監査と応答を書く(同じ Idempotency-Key なので、
 * order には 1 つの注文しかできない)。
 *
 * 監査の記録の項目
 * | 項目 | 値 |
 * |---|---|
 * | 誰が | `service` / `b2b-gateway` |
 * | 何を | `b2b.order.submit` / `b2b_order_submission` / 送信待ちの ID / 受け付けられたら `success`、受け付けられない・期限切れなら `failure` |
 * | どこへ | `http:/sales/v1/orders`(Gateway。INT-SALES-001) |
 * | 本文の代わり | 固定した要求の本文の SHA-256 |
 * | 補足 | 結果(accepted・refused・expired)・受け取りの記録の ID・注文番号・Idempotency-Key・order の注文の ID・拒否のステータス・試みた回数 |
 */
public class JdbcOrderSubmissions(
    private val dataSource: DataSource,
    private val log: AuditLog,
    private val clock: Clock = Clock.systemUTC(),
) : OrderSubmissions {
    override suspend fun pending(limit: Int): Result<List<PendingSubmission>, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(PENDING).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.submission()) } }
            }
        }

    override suspend fun accepted(
        submission: PendingSubmission,
        orderId: String,
        acknowledgement: StoredAcknowledgement?,
    ): Result<Unit, DomainError> = complete(submission, Completion(ACCEPTED, orderId = orderId), acknowledgement)

    override suspend fun refused(
        submission: PendingSubmission,
        status: Int,
        acknowledgement: StoredAcknowledgement,
    ): Result<Unit, DomainError> = complete(submission, Completion(REFUSED, refusedStatus = status), acknowledgement)

    override suspend fun expired(submission: PendingSubmission): Result<Unit, DomainError> = complete(submission, Completion(EXPIRED), null)

    override suspend fun failed(
        submission: PendingSubmission,
        errorCode: String,
    ): Result<Unit, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(FAILED).use { statement ->
                statement.setString(1, errorCode)
                statement.setObject(2, UUID.fromString(submission.id))
                statement.executeUpdate()
            }
        }

    /**
     * 期限のアラートのメトリクスの元([OrderSubmissionMetrics][io.eia.b2bgateway.adapters.order.OrderSubmissionMetrics])。取引先ごとの、送信待ちの業務の応答の期限のうちいちばん早いものと、
     * 直近 [window] に期限切れにした注文の数。
     */
    public suspend fun snapshot(window: Duration = EXPIRED_WINDOW): Result<SubmissionSnapshot, DomainError> =
        dataSource.inTransaction { connection ->
            val deadlines =
                connection.prepareStatement(EARLIEST_DEADLINES).use { statement ->
                    statement.executeQuery().use { rs ->
                        buildMap { while (rs.next()) put(rs.getString(1), rs.getTimestamp(2).toInstant()) }
                    }
                }
            val expired =
                connection.prepareStatement(EXPIRED_SINCE).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(clock.instant().minus(window)))
                    statement.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) } }
                }
            SubmissionSnapshot(deadlines, expired)
        }

    private suspend fun complete(
        submission: PendingSubmission,
        completion: Completion,
        acknowledgement: StoredAcknowledgement?,
    ): Result<Unit, DomainError> {
        val trace = CurrentTrace.get()
        return dataSource.inTransaction { connection ->
            val attempts =
                connection.prepareStatement(COMPLETE).use { statement ->
                    var index = 0
                    statement.setString(++index, completion.state)
                    statement.setString(++index, completion.orderId)
                    statement.setObject(++index, completion.refusedStatus)
                    statement.setObject(++index, UUID.fromString(submission.id))
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
                }
            // ほかの処理が先に結果を書いていたら、何もしない
            if (attempts != null) {
                acknowledgement?.let { insertAcknowledgement(connection, submission, it) }
                val event = auditEvent(submission, completion, attempts, trace)
                when (val appended = log.append(connection, event)) {
                    is Result.Ok -> Unit

                    // 追記できなければ、結果も残さない(送信待ちのまま。ADR-0017 §4)
                    is Result.Err -> throw AuditFailed(appended.error.code, appended.error is DomainError.Retryable)
                }
            }
        }
    }

    private fun insertAcknowledgement(
        connection: Connection,
        submission: PendingSubmission,
        acknowledgement: StoredAcknowledgement,
    ) {
        connection.prepareStatement(INSERT_ACKNOWLEDGEMENT).use { statement ->
            var index = 0
            statement.setObject(++index, UUID.randomUUID())
            statement.setObject(++index, UUID.fromString(submission.documentId))
            statement.setString(++index, submission.partnerId)
            statement.setString(++index, acknowledgement.kind.name)
            statement.setString(++index, acknowledgement.original.ref)
            statement.setString(++index, acknowledgement.original.sha256)
            statement.setString(++index, acknowledgement.readableRef)
            statement.executeUpdate()
        }
    }

    private fun auditEvent(
        submission: PendingSubmission,
        completion: Completion,
        attempts: Int,
        trace: CurrentTrace,
    ): AuditEvent =
        AuditEvent(
            occurredAt = clock.instant(),
            actor = Actor(ActorType.SERVICE, SERVICE),
            action = ACTION,
            target = AuditTarget(TARGET_TYPE, submission.id),
            outcome = if (completion.state == ACCEPTED) AuditOutcome.SUCCESS else AuditOutcome.FAILURE,
            destination = DESTINATION,
            payloadSha256 = (ChainHash.parse(sha256(submission.body)) as Result.Ok).value,
            correlationId = trace.correlationId,
            traceparent = trace.traceParent,
            details =
                buildMap {
                    put("outcome", completion.state.lowercase())
                    put("document_id", submission.documentId)
                    put("order_number", submission.orderNumber)
                    put("idempotency_key", submission.idempotencyKey)
                    put("attempts", attempts.toString())
                    completion.orderId?.let { put("order_id", it) }
                    completion.refusedStatus?.let { put("status", it.toString()) }
                },
        )

    private fun ResultSet.submission(): PendingSubmission =
        PendingSubmission(
            id = getString("id"),
            documentId = getString("document_id"),
            partnerId = getString("partner_id"),
            orderNumber = getString("order_number"),
            idempotencyKey = getString("idempotency_key"),
            body = getBytes("body"),
            header =
                InterchangeHeader(
                    serviceCharacters = checkNotNull(ServiceCharacters.fromUna(getString("service_characters"))) { "保存した UNA は 9 文字" },
                    characterSet = CharacterSet.valueOf(getString("character_set")),
                    syntaxVersion = getString("syntax_version"),
                    sender = PartyIdentification(getString("sender_id"), getString("sender_qualifier").orEmpty()),
                    recipient = PartyIdentification(getString("recipient_id"), getString("recipient_qualifier").orEmpty()),
                    controlReference = getString("control_reference"),
                    test = false,
                ),
            positiveAperak = getBoolean("positive_aperak"),
            retryUntil = getTimestamp("retry_until").toInstant().toKotlinInstant(),
        )

    /** 送信待ちの結果。 */
    private class Completion(
        val state: String,
        val orderId: String? = null,
        val refusedStatus: Int? = null,
    )

    private companion object {
        const val ACTION = "b2b.order.submit"
        const val TARGET_TYPE = "b2b_order_submission"
        const val SERVICE = "b2b-gateway"
        const val DESTINATION = "http:/sales/v1/orders"
        const val ACCEPTED = "ACCEPTED"
        const val REFUSED = "REFUSED"
        const val EXPIRED = "EXPIRED"

        const val PENDING = """
            SELECT id::text, document_id::text, partner_id, order_number, idempotency_key, body, service_characters, character_set,
              syntax_version, sender_id, sender_qualifier, recipient_id, recipient_qualifier, control_reference, positive_aperak, retry_until
            FROM b2b.order_submission WHERE state = 'PENDING' ORDER BY created_at, id LIMIT ?
        """
        const val COMPLETE = """
            UPDATE b2b.order_submission
            SET state = ?, order_id = ?, refused_status = ?, attempts = attempts + 1, completed_at = clock_timestamp()
            WHERE id = ? AND state = 'PENDING'
            RETURNING attempts
        """
        const val FAILED = """
            UPDATE b2b.order_submission SET attempts = attempts + 1, last_error = ? WHERE id = ? AND state = 'PENDING'
        """
        val EXPIRED_WINDOW: Duration = Duration.ofHours(24)
        const val EXPIRED_SINCE =
            "SELECT partner_id, count(*) FROM b2b.order_submission WHERE state = 'EXPIRED' AND completed_at > ? GROUP BY partner_id"
        const val EARLIEST_DEADLINES =
            "SELECT partner_id, min(aperak_deadline) FROM b2b.order_submission WHERE state = 'PENDING' GROUP BY partner_id"
        const val INSERT_ACKNOWLEDGEMENT = """
            INSERT INTO b2b.acknowledgement (id, document_id, partner_id, kind, original_ref, original_sha256, readable_ref)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """
    }
}
