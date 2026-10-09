package io.eia.payment.adapters.out.persistence

import io.eia.payment.application.port.outbound.ProcessedCommands
import io.eia.platform.inbox.Inbox
import io.eia.platform.inbox.InboxError
import io.eia.platform.inbox.InboxStorageUnavailable
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.SQLException
import kotlin.uuid.Uuid

/**
 * [ProcessedCommands] の実装(`platform/inbox`。ADR-0028 §3)。今のトランザクション(業務の更新と同じ)で記録する。
 *
 * @param consumerGroup payment のコマンドの Consumer Group(`payment.command`)
 */
public class InboxProcessedCommands(
    private val database: Database,
    private val consumerGroup: String,
    private val inbox: Inbox = Inbox(),
) : ProcessedCommands {
    override suspend fun markProcessed(
        messageId: String,
        topic: String,
    ): Result<Boolean, DomainError> {
        val id =
            try {
                Uuid.parse(messageId)
            } catch (_: IllegalArgumentException) {
                return err(ValidationError.of("ce_id", "UUID の形式にしてください"))
            }
        return database.inCurrentTransaction { connection ->
            inbox
                .markProcessed(connection, consumerGroup, id, topic)
                .map { it == Inbox.Receipt.FIRST }
                .mapError(::classify)
        }
    }

    override suspend fun purgeExpired(batchSize: Int): Result<Int, DomainError> =
        try {
            database.newTransaction { inbox.purgeExpired(jdbcConnection(), batchSize = batchSize).mapError(::classify) }
        } catch (e: SQLException) {
            err(SqlErrors.classify(e))
        }

    private fun classify(error: InboxError): DomainError =
        if (error is InboxStorageUnavailable) UnavailableError(error.message) else error.asDomainError()
}
