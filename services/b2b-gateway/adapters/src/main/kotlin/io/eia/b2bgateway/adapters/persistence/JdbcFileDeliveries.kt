package io.eia.b2bgateway.adapters.persistence

import io.eia.b2bgateway.application.port.outbound.FileDeliveries
import io.eia.b2bgateway.application.port.outbound.FileDelivery
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
import javax.sql.DataSource

/**
 * 取引先へのファイルの送信の記録(表 `b2b.file_delivery`。ADR-0040 §7)。記録と監査(`b2b.file.send`。ADR-0017)を 1 つのトランザクションで書く。
 *
 * 監査の記録の項目
 * | 項目 | 値 |
 * |---|---|
 * | 誰が | `service` / `b2b-gateway` |
 * | 何を | `b2b.file.send` / `b2b_file_delivery` / `{ファイルの名前}/{取引先の ID}` / `success` |
 * | どこへ | `partner:{取引先の ID}` |
 * | 本文の代わり | 送ったデータファイル(暗号化する前)の SHA-256(マニフェストと照らし合わせたもの) |
 * | 補足 | 取引先の側の正式名・取引先の側にすでにあったか |
 *
 * 2 つの処理が同じ組を送っても、取引先の側の正式名が同じなので二重には届かず、先に記録したほうだけが監査を書く。
 */
public class JdbcFileDeliveries(
    private val dataSource: DataSource,
    private val log: AuditLog,
    private val clock: Clock = Clock.systemUTC(),
) : FileDeliveries {
    override suspend fun delivered(name: String): Result<Set<String>, DomainError> =
        dataSource.inTransaction { connection ->
            connection.prepareStatement(DELIVERED).use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    override suspend fun record(delivery: FileDelivery): Result<Unit, DomainError> {
        val trace = CurrentTrace.get()
        return dataSource.inTransaction { connection ->
            val inserted =
                connection.prepareStatement(INSERT).use { statement ->
                    var index = 0
                    statement.setString(++index, delivery.name)
                    statement.setString(++index, delivery.partnerId)
                    statement.setString(++index, delivery.remoteName)
                    statement.setString(++index, delivery.sha256)
                    statement.setBoolean(++index, delivery.alreadyPresent)
                    statement.executeUpdate()
                }
            if (inserted == 1) {
                val event =
                    AuditEvent(
                        occurredAt = clock.instant(),
                        actor = Actor(ActorType.SERVICE, SERVICE),
                        action = ACTION,
                        target = AuditTarget(TARGET_TYPE, "${delivery.name}/${delivery.partnerId}"),
                        outcome = AuditOutcome.SUCCESS,
                        destination = "partner:${delivery.partnerId}",
                        payloadSha256 = (ChainHash.parse(delivery.sha256) as Result.Ok).value,
                        correlationId = trace.correlationId,
                        traceparent = trace.traceParent,
                        details = mapOf("remote_name" to delivery.remoteName, "already_present" to delivery.alreadyPresent.toString()),
                    )
                when (val appended = log.append(connection, event)) {
                    is Result.Ok -> Unit

                    // 追記できなければ、記録も残さない(次の回に送り直す。取引先の側にあれば ALREADY_PRESENT になる。ADR-0017 §4)
                    is Result.Err -> throw AuditFailed(appended.error.code, appended.error is DomainError.Retryable)
                }
            }
        }
    }

    private companion object {
        const val ACTION = "b2b.file.send"
        const val TARGET_TYPE = "b2b_file_delivery"
        const val SERVICE = "b2b-gateway"
        const val DELIVERED = "SELECT partner_id FROM b2b.file_delivery WHERE name = ?"
        const val INSERT = """
            INSERT INTO b2b.file_delivery (name, partner_id, remote_name, sha256, already_present) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (name, partner_id) DO NOTHING
        """
    }
}
