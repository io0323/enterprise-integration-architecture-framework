package io.eia.order.adapters.out.saga

import io.eia.order.adapters.out.persistence.ExposedJdbcSession
import io.eia.order.adapters.out.persistence.JdbcSession
import io.eia.order.application.port.outbound.SagaStore
import io.eia.order.domain.OrderId
import io.eia.order.domain.Saga
import io.eia.order.domain.SagaFailure
import io.eia.order.domain.SagaState
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.time.Duration

/**
 * [SagaStore] の PostgreSQL の実装(表 `order_saga`。db/order/V3)。
 *
 * **段の期限は DB の時計で決める**(ADR-0029 §6。ご指示): 書くときは `clock_timestamp() + make_interval(secs => 段の期限)`、
 * 期限切れの判定は `deadline_at <= clock_timestamp()`。アプリの時計はどこにも使わない(このクラスは時計を持たない)。
 * 期限切れの取り出しは `FOR UPDATE SKIP LOCKED`(ほかのインスタンスが処理中の Saga を飛ばす)。
 */
public class ExposedSagaStore internal constructor(
    private val session: JdbcSession,
) : SagaStore {
    public constructor(database: Database) : this(ExposedJdbcSession(database))

    override suspend fun insert(
        saga: Saga,
        stepTimeout: Duration?,
    ): Result<Unit, DomainError> =
        session.run { connection ->
            connection.prepareStatement(INSERT).use { statement ->
                var index = 0
                statement.setString(++index, saga.id)
                statement.setString(++index, saga.orderId.value)
                statement.setString(++index, saga.state.name)
                statement.setString(++index, saga.failure?.name)
                statement.setInt(++index, saga.resends)
                index = setTimeout(statement, index, stepTimeout)
                statement.executeUpdate()
            }
            ok(Unit)
        }

    override suspend fun findForUpdate(sagaId: String): Result<Saga?, DomainError> =
        session.run { connection ->
            connection.prepareStatement(SELECT_FOR_UPDATE).use { statement ->
                statement.setString(1, sagaId)
                statement.executeQuery().use { rows -> readFirst(rows) }
            }
        }

    override suspend fun save(
        saga: Saga,
        stepTimeout: Duration?,
    ): Result<Unit, DomainError> =
        session.run { connection ->
            val updated =
                connection.prepareStatement(UPDATE).use { statement ->
                    var index = 0
                    statement.setString(++index, saga.state.name)
                    statement.setString(++index, saga.failure?.name)
                    statement.setInt(++index, saga.resends)
                    index = setTimeout(statement, index, stepTimeout)
                    statement.setString(++index, saga.id)
                    statement.executeUpdate()
                }
            if (updated == 1) ok(Unit) else err(UnexpectedError("Saga ${saga.id} がありません"))
        }

    override suspend fun lockExpired(limit: Int): Result<List<Saga>, DomainError> =
        session.run { connection -> lockExpired(connection, limit) }

    private fun lockExpired(
        connection: Connection,
        limit: Int,
    ): Result<List<Saga>, DomainError> =
        connection.prepareStatement(LOCK_EXPIRED).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows -> readAll(rows) }
        }

    private fun readFirst(rows: ResultSet): Result<Saga?, DomainError> = if (rows.next()) read(rows) else ok(null)

    private fun readAll(rows: ResultSet): Result<List<Saga>, DomainError> {
        val sagas = mutableListOf<Saga>()
        while (rows.next()) {
            when (val saga = read(rows)) {
                is Result.Ok -> sagas += checkNotNull(saga.value)
                is Result.Err -> return saga
            }
        }
        return ok(sagas)
    }

    /** 段の期限(秒。マイクロ秒の精度)を入れる。NULL(終端)なら `clock_timestamp() + NULL` で期限も NULL になる。 */
    private fun setTimeout(
        statement: PreparedStatement,
        start: Int,
        stepTimeout: Duration?,
    ): Int {
        var index = start
        if (stepTimeout == null) {
            statement.setNull(++index, Types.DOUBLE)
        } else {
            statement.setDouble(++index, stepTimeout.inWholeMicroseconds / MICROS_PER_SECOND)
        }
        return index
    }

    private fun read(rows: ResultSet): Result<Saga?, DomainError> =
        when (val orderId = OrderId.parse(rows.getString("order_id"))) {
            is Result.Err -> {
                err(UnexpectedError("保存された Saga の注文 ID が不正です"))
            }

            is Result.Ok -> {
                ok(
                    Saga(
                        id = rows.getString("saga_id"),
                        orderId = orderId.value,
                        state = SagaState.valueOf(rows.getString("state")),
                        failure = rows.getString("failure")?.let(SagaFailure::valueOf),
                        resends = rows.getInt("resends"),
                    ),
                )
            }
        }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000.0
        const val COLUMNS = "saga_id, order_id, state, failure, resends"

        /** 期限 = DB の時計 + 段の期限。段の期限が NULL(終端)なら期限なし。 */
        const val DEADLINE = "clock_timestamp() + make_interval(secs => ?::double precision)"
        val INSERT =
            """
            INSERT INTO order_saga (saga_id, order_id, state, failure, resends, deadline_at)
            VALUES (?, ?, ?, ?, ?, $DEADLINE)
            """.trimIndent()
        const val SELECT_FOR_UPDATE = "SELECT $COLUMNS FROM order_saga WHERE saga_id = ? FOR UPDATE"
        val UPDATE =
            """
            UPDATE order_saga SET state = ?, failure = ?, resends = ?, deadline_at = $DEADLINE, updated_at = clock_timestamp()
            WHERE saga_id = ?
            """.trimIndent()
        val LOCK_EXPIRED =
            """
            SELECT $COLUMNS FROM order_saga
            WHERE deadline_at <= clock_timestamp()
            ORDER BY deadline_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """.trimIndent()
    }
}
