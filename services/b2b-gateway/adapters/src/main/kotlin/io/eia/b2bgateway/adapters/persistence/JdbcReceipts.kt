package io.eia.b2bgateway.adapters.persistence

import io.eia.b2bgateway.application.port.inbound.Receipt
import io.eia.b2bgateway.application.port.outbound.DocumentRecord
import io.eia.b2bgateway.application.port.outbound.ReceiptRecords
import io.eia.b2bgateway.application.port.outbound.ReceivedInterchanges
import io.eia.b2bgateway.application.port.outbound.ReceivedOrders
import io.eia.b2bgateway.domain.receipt.DuplicateCheck
import io.eia.b2bgateway.domain.receipt.InterchangeKey
import io.eia.b2bgateway.domain.receipt.PreviousReceipt
import io.eia.b2bgateway.domain.validation.InterchangeHeader
import io.eia.b2bgateway.domain.validation.InterchangeResult
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
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.toJavaInstant

/**
 * 受け取りの記録(ADR-0040 §5・ADR-0041 §11)。表は `b2b.received_document`・`received_order`・`acknowledgement`。
 * 1 つの文書の記録・受け付けた注文・送る応答・監査(`b2b.document.receive`。ADR-0017)を 1 つのトランザクションで書く。
 *
 * 監査の記録の項目
 * | 項目 | 値 |
 * |---|---|
 * | 誰が | `partner` / 取引先の ID |
 * | 何を | `b2b.document.receive` / `b2b_document` / 受け取りの記録の ID / 交換を受け付けた・送り直しなら `success`、拒否・読めないなら `failure` |
 * | 本文の代わり | 原本(受け取った PGP の暗号文)の SHA-256 と、原本の場所 |
 * | 補足 | 段階(decryption・syntax・schema・business・none)・復号した交換の SHA-256・交換の管理番号・メッセージの数と拒否の数・拒否した注文の数 |
 */
public class JdbcReceipts(
    private val dataSource: DataSource,
    private val log: AuditLog,
) : ReceiptRecords,
    ReceivedInterchanges,
    ReceivedOrders {
    override suspend fun find(key: InterchangeKey): Result<PreviousReceipt?, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(FIND_FIRST_RECEIPT).use { statement ->
                var index = 0
                statement.setString(++index, key.partnerId)
                statement.setString(++index, key.sender.id)
                statement.setString(++index, key.sender.qualifier)
                statement.setString(++index, key.recipient.id)
                statement.setString(++index, key.recipient.qualifier)
                statement.setString(++index, key.controlReference)
                statement.executeQuery().use { rs -> if (rs.next()) PreviousReceipt(rs.getString(1), rs.getString(2)) else null }
            }
        }

    override suspend fun exists(
        partnerId: String,
        orderNumber: String,
    ): Result<Boolean, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(ORDER_EXISTS).use { statement ->
                statement.setString(1, partnerId)
                statement.setString(2, orderNumber)
                statement.executeQuery().use { it.next() }
            }
        }

    override suspend fun record(document: DocumentRecord): Result<String, DomainError> {
        val id = UUID.randomUUID()
        val trace = CurrentTrace.get()
        // 同じ交換を 2 つの処理が同時に記録したとき(初めての受け取りの一意索引・注文番号の主キー)は、やり直せば送り直し・重複になる
        return dataSource.inTransaction(retryable = setOf(UNIQUE_VIOLATION)) { connection ->
            val summary = Summary.of(document)
            insertDocument(connection, id, document, summary)
            insertOrders(connection, id, document)
            insertAcknowledgements(connection, id, document)
            val event = auditEvent(id, document, summary, trace)
            when (val appended = log.append(connection, event)) {
                is Result.Ok -> Unit

                // 追記できなければ、記録も残さない(業務の記録と監査の確定をそろえる。ADR-0017 §4)
                is Result.Err -> throw AuditFailed(appended.error.code, appended.error is DomainError.Retryable)
            }
            id.toString()
        }
    }

    private fun insertDocument(
        connection: Connection,
        id: UUID,
        document: DocumentRecord,
        summary: Summary,
    ) {
        val header = summary.header
        connection.prepareStatement(INSERT_DOCUMENT).use { statement ->
            var index = 0
            statement.setObject(++index, id)
            statement.setString(++index, document.file.partnerId)
            statement.setString(++index, document.file.fileName)
            statement.setTimestamp(++index, Timestamp.from(document.file.receivedAt.toJavaInstant()))
            statement.setString(++index, document.file.original.ref)
            statement.setString(++index, document.file.original.sha256)
            statement.setString(++index, summary.outcome)
            statement.setString(++index, summary.stage)
            statement.setString(++index, document.refusal)
            statement.setString(++index, document.opened?.sha256)
            statement.setString(++index, document.opened?.signer)
            statement.setString(++index, document.opened?.readableRef)
            statement.setString(++index, header?.sender?.id)
            statement.setString(++index, header?.sender?.qualifier)
            statement.setString(++index, header?.recipient?.id)
            statement.setString(++index, header?.recipient?.qualifier)
            statement.setString(++index, header?.controlReference)
            statement.setBoolean(++index, header?.test == true)
            statement.setBoolean(++index, summary.firstReceipt)
            val previous = summary.previousDocument
            if (previous == null) statement.setNull(++index, Types.OTHER) else statement.setObject(++index, UUID.fromString(previous))
            statement.executeUpdate()
        }
    }

    /** 受け付けた注文(試験の交換の注文は入れない。社内に取り込まないため)。 */
    private fun insertOrders(
        connection: Connection,
        id: UUID,
        document: DocumentRecord,
    ) {
        val receipt = document.receipt ?: return
        if (receipt.header()?.test == true) return
        receipt.orders.filter { it.accepted }.forEach { decision ->
            connection.prepareStatement(INSERT_ORDER).use { statement ->
                var index = 0
                statement.setString(++index, document.file.partnerId)
                statement.setString(++index, decision.order.orderNumber)
                statement.setObject(++index, id)
                statement.setString(++index, decision.messageReference)
                statement.setTimestamp(++index, Timestamp.from(document.file.receivedAt.toJavaInstant()))
                statement.executeUpdate()
            }
        }
    }

    /** 送る応答。送り直しなら、前の記録の応答と同じ原本を指す行を足す(送信の仕組みがもう一度送る)。 */
    private fun insertAcknowledgements(
        connection: Connection,
        id: UUID,
        document: DocumentRecord,
    ) {
        val resend = document.receipt?.duplicate as? DuplicateCheck.Resend
        if (resend != null) {
            connection.prepareStatement(COPY_ACKNOWLEDGEMENTS).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, UUID.fromString(resend.previous.documentId))
                statement.executeUpdate()
            }
            return
        }
        document.acknowledgements.forEach { acknowledgement ->
            connection.prepareStatement(INSERT_ACKNOWLEDGEMENT).use { statement ->
                var index = 0
                statement.setObject(++index, UUID.randomUUID())
                statement.setObject(++index, id)
                statement.setString(++index, document.file.partnerId)
                statement.setString(++index, acknowledgement.kind.name)
                statement.setString(++index, acknowledgement.original.ref)
                statement.setString(++index, acknowledgement.original.sha256)
                statement.setString(++index, acknowledgement.readableRef)
                statement.executeUpdate()
            }
        }
    }

    private fun auditEvent(
        id: UUID,
        document: DocumentRecord,
        summary: Summary,
        trace: CurrentTrace,
    ): AuditEvent {
        val receipt = document.receipt
        val details =
            buildMap {
                put("stage", summary.stage)
                put("outcome", summary.outcome.lowercase())
                document.refusal?.let { put("refusal", it) }
                document.opened?.sha256?.let { put("interchange_sha256", it) }
                summary.header?.controlReference?.let { put("control_reference", it) }
                summary.previousDocument?.let { put("previous_document", it) }
                if (summary.header?.test == true) put("test", "true")
                (receipt?.result as? InterchangeResult.Checked)?.let { checked ->
                    put("messages", checked.messages.size.toString())
                    put("messages_rejected", checked.messages.count { !it.accepted }.toString())
                    put("orders_rejected", receipt.orders.count { !it.accepted }.toString())
                }
            }
        return AuditEvent(
            occurredAt = document.file.receivedAt.toJavaInstant(),
            actor = Actor(ActorType.PARTNER, document.file.partnerId),
            action = ACTION,
            target = AuditTarget(TARGET_TYPE, id.toString()),
            outcome = if (summary.outcome in SUCCESSFUL) AuditOutcome.SUCCESS else AuditOutcome.FAILURE,
            payloadSha256 = (ChainHash.parse(document.file.original.sha256) as Result.Ok).value,
            payloadRef = document.file.original.ref,
            correlationId = trace.correlationId,
            traceparent = trace.traceParent,
            details = details,
        )
    }

    /** 記録の分類(結果・段階・初めての受け取りか)。 */
    private class Summary(
        val outcome: String,
        val stage: String,
        val header: InterchangeHeader?,
        val firstReceipt: Boolean,
        val previousDocument: String?,
    ) {
        companion object {
            fun of(document: DocumentRecord): Summary {
                val receipt = document.receipt ?: return Summary("UNREADABLE", "decryption", null, false, null)
                val header = receipt.header()
                val duplicate = receipt.duplicate
                val (outcome, stage) =
                    when {
                        duplicate is DuplicateCheck.Resend -> "RESENT" to "none"
                        receipt.result is InterchangeResult.Unreadable -> "NO_ENVELOPE" to "syntax"
                        receipt.result is InterchangeResult.Rejected -> "REJECTED" to "syntax"
                        else -> "CHECKED" to stageOf(receipt)
                    }
                return Summary(
                    outcome = outcome,
                    stage = stage,
                    header = header,
                    firstReceipt = header != null && duplicate == DuplicateCheck.First,
                    previousDocument = (duplicate as? DuplicateCheck.Resend)?.previous?.documentId,
                )
            }

            /** 交換の枠を受け付けた交換で、いちばん早い段階の拒否(メッセージの枠は構文、中身はスキーマ、注文は業務)。 */
            private fun stageOf(receipt: Receipt): String {
                val messages = (receipt.result as InterchangeResult.Checked).messages
                return when {
                    messages.any { it.header != null } -> "syntax"
                    messages.any { it.errors.isNotEmpty() } -> "schema"
                    receipt.orders.any { !it.accepted } -> "business"
                    else -> "none"
                }
            }
        }
    }

    /** 監査を追記できなかった(トランザクションを巻き戻す。理由は監査のエラーのコードだけ。一時的な失敗は、やり直せる SQLSTATE にする)。 */
    private class AuditFailed(
        code: String,
        retryable: Boolean,
    ) : SQLException("監査の記録に失敗しました($code)", if (retryable) "40001" else "XX000")

    private companion object {
        const val ACTION = "b2b.document.receive"
        const val TARGET_TYPE = "b2b_document"
        val SUCCESSFUL = setOf("CHECKED", "RESENT")

        const val FIND_FIRST_RECEIPT = """
            SELECT interchange_sha256, id::text FROM b2b.received_document
            WHERE first_receipt AND partner_id = ? AND sender_id = ? AND sender_qualifier = ? AND recipient_id = ? AND recipient_qualifier = ?
              AND control_reference = ?
        """
        const val ORDER_EXISTS = "SELECT 1 FROM b2b.received_order WHERE partner_id = ? AND order_number = ?"
        const val INSERT_DOCUMENT = """
            INSERT INTO b2b.received_document (id, partner_id, file_name, received_at, original_ref, original_sha256, outcome, stage, refusal,
              interchange_sha256, signer, readable_ref, sender_id, sender_qualifier, recipient_id, recipient_qualifier, control_reference, test,
              first_receipt, previous_document)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_ORDER =
            "INSERT INTO b2b.received_order (partner_id, order_number, document_id, message_reference, received_at) VALUES (?, ?, ?, ?, ?)"
        const val INSERT_ACKNOWLEDGEMENT = """
            INSERT INTO b2b.acknowledgement (id, document_id, partner_id, kind, original_ref, original_sha256, readable_ref)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """
        const val COPY_ACKNOWLEDGEMENTS = """
            INSERT INTO b2b.acknowledgement (id, document_id, partner_id, kind, original_ref, original_sha256, readable_ref)
            SELECT gen_random_uuid(), ?, partner_id, kind, original_ref, original_sha256, readable_ref
            FROM b2b.acknowledgement WHERE document_id = ?
        """
    }
}

private fun Receipt.header(): InterchangeHeader? =
    when (val result = result) {
        is InterchangeResult.Unreadable -> null
        is InterchangeResult.Rejected -> result.header
        is InterchangeResult.Checked -> result.header
    }
