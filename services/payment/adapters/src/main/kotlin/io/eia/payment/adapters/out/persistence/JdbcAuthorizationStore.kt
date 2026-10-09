package io.eia.payment.adapters.out.persistence

import io.eia.payment.application.port.outbound.AuthorizationIds
import io.eia.payment.application.port.outbound.AuthorizationStore
import io.eia.payment.domain.Amount
import io.eia.payment.domain.Authorization
import io.eia.payment.domain.AuthorizationStatus
import io.eia.payment.domain.DeclineReason
import io.eia.platform.messagingkafka.EventIds
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.SQLException
import java.sql.Types
import kotlin.time.Duration

/**
 * [AuthorizationStore] の JDBC の実装(表 `authorization_record`。db/payment/V1)。
 * 終わった時刻(`settled_at`)は DB の時計(`clock_timestamp()`)で書き、保持期間の判定も DB の時計で行う(ADR-0029 §5)。
 */
public class JdbcAuthorizationStore(
    private val database: Database,
) : AuthorizationStore {
    override suspend fun findForUpdate(sagaId: String): Result<Authorization?, DomainError> =
        database.inCurrentTransaction { connection ->
            connection.prepareStatement(SELECT_FOR_UPDATE).use { statement ->
                statement.setString(1, sagaId)
                statement.executeQuery().use { rows ->
                    ok(
                        if (rows.next()) {
                            val minor = rows.getLong("amount_minor").takeUnless { rows.wasNull() }
                            Authorization(
                                sagaId = sagaId,
                                orderId = rows.getString("order_id"),
                                status = AuthorizationStatus.valueOf(rows.getString("status")),
                                authorizationId = rows.getString("authorization_id"),
                                decline = rows.getString("decline_reason")?.let(DeclineReason::valueOf),
                                amount = minor?.let { Amount(it, rows.getString("currency")) },
                            )
                        } else {
                            null
                        },
                    )
                }
            }
        }

    override suspend fun insert(authorization: Authorization): Result<Unit, DomainError> =
        database.inCurrentTransaction { connection ->
            connection.prepareStatement(INSERT).use { statement ->
                var index = 0
                statement.setString(++index, authorization.sagaId)
                statement.setString(++index, authorization.orderId)
                statement.setString(++index, authorization.status.name)
                statement.setString(++index, authorization.authorizationId)
                statement.setString(++index, authorization.decline?.name)
                authorization.amount?.let { statement.setLong(++index, it.minorUnits) } ?: statement.setNull(++index, Types.BIGINT)
                statement.setString(++index, authorization.amount?.currency)
                statement.setBoolean(++index, authorization.status.isSettled)
                statement.executeUpdate()
            }
            ok(Unit)
        }

    override suspend fun markVoided(sagaId: String): Result<Unit, DomainError> =
        database.inCurrentTransaction { connection ->
            val updated =
                connection.prepareStatement(MARK_VOIDED).use { statement ->
                    statement.setString(1, sagaId)
                    statement.executeUpdate()
                }
            if (updated == 1) ok(Unit) else err(UnexpectedError("承認している記録がありません(状態が変わった)"))
        }

    override suspend fun purgeSettled(
        retention: Duration,
        batchSize: Int,
    ): Result<Int, DomainError> =
        try {
            // 1 回ずつ短いトランザクションで消す(長いロックを避ける)
            ok(
                database.newTransaction {
                    jdbcConnection().prepareStatement(PURGE).use { statement ->
                        statement.setLong(1, retention.inWholeSeconds)
                        statement.setInt(2, batchSize)
                        statement.executeUpdate()
                    }
                },
            )
        } catch (e: SQLException) {
            err(SqlErrors.classify(e))
        }

    private companion object {
        val SELECT_FOR_UPDATE =
            """
            SELECT order_id, status, authorization_id, decline_reason, amount_minor, currency
            FROM authorization_record WHERE saga_id = ? FOR UPDATE
            """.trimIndent()
        val INSERT =
            """
            INSERT INTO authorization_record (saga_id, order_id, status, authorization_id, decline_reason, amount_minor, currency, settled_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN clock_timestamp() END)
            """.trimIndent()
        val MARK_VOIDED =
            """
            UPDATE authorization_record SET status = 'VOIDED', settled_at = clock_timestamp()
            WHERE saga_id = ? AND status = 'AUTHORIZED'
            """.trimIndent()
        val PURGE =
            """
            DELETE FROM authorization_record WHERE saga_id IN (
                SELECT saga_id FROM authorization_record
                WHERE settled_at < clock_timestamp() - make_interval(secs => ?)
                ORDER BY settled_at
                LIMIT ?
            )
            """.trimIndent()
    }
}

/** 承認 ID の採番(UUIDv7。時刻の順に並ぶ)。 */
public class UuidV7AuthorizationIds(
    private val ids: EventIds = EventIds(),
) : AuthorizationIds {
    override fun next(): String = ids.next().toString()
}
