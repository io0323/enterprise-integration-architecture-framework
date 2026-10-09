package io.eia.order.adapters.out.saga

import io.eia.order.adapters.out.persistence.currentTransaction
import io.eia.order.adapters.out.persistence.jdbcConnection
import io.eia.order.application.port.outbound.ProcessedReplies
import io.eia.order.application.port.outbound.SagaIdGenerator
import io.eia.platform.inbox.Inbox
import io.eia.platform.inbox.InboxStorageUnavailable
import io.eia.platform.messagingkafka.EventIds
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.map
import io.eia.shared.kernel.mapError
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection
import kotlin.uuid.Uuid

/**
 * [ProcessedReplies] の実装(`platform/inbox`。ADR-0028 §3)。今のトランザクション(Saga の更新と同じ)で記録する。
 *
 * @param consumerGroup 返信の Consumer Group(`order.saga`)
 */
public class InboxProcessedReplies(
    private val database: Database,
    private val consumerGroup: String,
    private val inbox: Inbox = Inbox(),
) : ProcessedReplies {
    override suspend fun markProcessed(
        messageId: String,
        topic: String,
    ): Result<Boolean, DomainError> {
        val id = parseUuid(messageId)
        val transaction = database.currentTransaction()
        return when {
            id == null -> {
                err(ValidationError.of("ce_id", "UUID の形式にしてください"))
            }

            transaction == null -> {
                err(UnexpectedError("返信の冪等消費の記録はトランザクションの中で書いてください"))
            }

            else -> {
                markProcessed(transaction.jdbcConnection(), id, topic)
            }
        }
    }

    private fun markProcessed(
        connection: Connection,
        id: Uuid,
        topic: String,
    ): Result<Boolean, DomainError> =
        inbox
            .markProcessed(connection, consumerGroup, id, topic)
            .map { it == Inbox.Receipt.FIRST }
            .mapError { error -> if (error is InboxStorageUnavailable) UnavailableError(error.message) else error.asDomainError() }
}

private fun parseUuid(value: String): Uuid? =
    try {
        Uuid.parse(value)
    } catch (_: IllegalArgumentException) {
        null
    }

/** Saga ID の採番(UUIDv7。時刻の順に並ぶ)。 */
public class UuidV7SagaIds(
    private val ids: EventIds = EventIds(),
) : SagaIdGenerator {
    override fun next(): String = ids.next().toString()
}
