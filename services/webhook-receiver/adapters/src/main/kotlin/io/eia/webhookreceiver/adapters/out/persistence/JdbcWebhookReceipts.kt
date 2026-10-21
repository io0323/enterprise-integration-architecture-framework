package io.eia.webhookreceiver.adapters.out.persistence

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.mapError
import io.eia.shared.kernel.ok
import io.eia.webhookreceiver.application.port.outbound.WebhookReceipts
import io.eia.webhookreceiver.domain.WebhookReceipt
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * [WebhookReceipts] の JDBC の実装(表 `webhook_receipt`。本文の列はない。ADR-0035 §3)。
 *
 * - 記録は `INSERT ... ON CONFLICT (webhook_id) DO NOTHING`。同じ ID の同時の挿入では、後の方は先の方の確定を待ってから衝突する
 *   (先の方が取り消せば、後の方が挿入する)。衝突したら記録済みの行を読む。
 * - 時刻はマイクロ秒に丸めて保存する(PostgreSQL の timestamptz の精度)。保持期間の判定は DB の時計で行う。
 */
public class JdbcWebhookReceipts(
    private val database: Database,
) : WebhookReceipts {
    override suspend fun insertIfAbsent(receipt: WebhookReceipt): Result<WebhookReceipt?, DomainError> =
        database.inCurrentTransaction { connection ->
            val inserted =
                connection.prepareStatement(INSERT).use { statement ->
                    var index = 0
                    statement.setString(++index, receipt.webhookId)
                    statement.setString(++index, receipt.source)
                    statement.setString(++index, receipt.bodySha256)
                    statement.setTimestamp(++index, Timestamp.from(receipt.signedAt.toJavaInstant()))
                    statement.setTimestamp(++index, Timestamp.from(receipt.receivedAt.toJavaInstant()))
                    statement.executeUpdate()
                }
            if (inserted == 1) ok(null) else existing(connection, receipt.webhookId)
        }

    override suspend fun purgeOlderThan(
        retention: Duration,
        limit: Int,
    ): Result<Int, DomainError> =
        database.newTransaction {
            database.inCurrentTransaction { connection ->
                connection.prepareStatement(PURGE).use { statement ->
                    statement.setLong(1, retention.inWholeSeconds)
                    statement.setInt(2, limit)
                    ok(statement.executeUpdate())
                }
            }
        }

    private fun existing(
        connection: Connection,
        webhookId: String,
    ): Result<WebhookReceipt?, DomainError> =
        connection.prepareStatement(SELECT).use { statement ->
            statement.setString(1, webhookId)
            statement.executeQuery().use { rs ->
                check(rs.next()) { "衝突した記録が見つかりません" }
                rs.toReceipt()
            }
        }

    private fun ResultSet.toReceipt(): Result<WebhookReceipt?, DomainError> =
        WebhookReceipt
            .of(
                getString("webhook_id"),
                getString("source"),
                getString("body_sha256"),
                instant("signed_at"),
                instant("received_at"),
            ).mapError { it as DomainError }
            .flatMap { ok(it) }

    private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant().toKotlinInstant()

    private companion object {
        const val INSERT =
            "INSERT INTO webhook_receipt (webhook_id, source, body_sha256, signed_at, received_at) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (webhook_id) DO NOTHING"
        const val SELECT = "SELECT webhook_id, source, body_sha256, signed_at, received_at FROM webhook_receipt WHERE webhook_id = ?"

        // 古い順に上限の件数まで(長いロックを避ける)。保持期間の判定は DB の時計
        const val PURGE =
            "DELETE FROM webhook_receipt WHERE webhook_id IN (" +
                "SELECT webhook_id FROM webhook_receipt WHERE received_at < clock_timestamp() - make_interval(secs => ?) " +
                "ORDER BY received_at LIMIT ?)"
    }
}
