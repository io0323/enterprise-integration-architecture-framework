package io.eia.order.adapters.out.persistence

import io.eia.order.application.port.outbound.OrderRepository
import io.eia.order.application.port.outbound.RepublishableOrder
import io.eia.order.application.port.outbound.RepublishableOrders
import io.eia.order.domain.OrderId
import io.eia.order.domain.SagaFailure
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Timestamp
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/** [RepublishableOrders] の PostgreSQL の実装(ADR-0046)。注文の時刻の範囲は `orders_ordered_at` の索引で読む。 */
public class ExposedRepublishableOrders internal constructor(
    private val session: JdbcSession,
    private val repository: OrderRepository,
) : RepublishableOrders {
    public constructor(database: Database, repository: OrderRepository) : this(ExposedJdbcSession(database), repository)

    private class Row(
        val id: String,
        val updatedAt: Instant,
        val failure: String?,
    )

    @Suppress("ReturnCount") // 読み取りの失敗と、値の誤りで返す
    override suspend fun page(
        from: Instant,
        to: Instant,
        afterId: String?,
        limit: Int,
    ): Result<List<RepublishableOrder>, DomainError> {
        val rows = readRows(from, to, afterId, limit).getOrElse { return err(it) }
        if (rows.isEmpty()) return ok(emptyList())
        val ids = rows.map { OrderId.parse(it.id).getOrElse { _ -> return err(UnexpectedError("保存された注文の ID が不正です")) } }
        val orders = repository.findByIds(ids.toSet()).getOrElse { return err(it) }.associateBy { it.id.value }
        return ok(
            rows.mapNotNull { row ->
                orders[row.id]?.let { order ->
                    RepublishableOrder(order, row.failure?.let(SagaFailure::valueOf), row.updatedAt)
                }
            },
        )
    }

    private suspend fun readRows(
        from: Instant,
        to: Instant,
        afterId: String?,
        limit: Int,
    ): Result<List<Row>, DomainError> =
        session.run { connection ->
            connection.prepareStatement(SELECT).use { statement ->
                var index = 0
                statement.setTimestamp(++index, Timestamp.from(from.toJavaInstant()))
                statement.setTimestamp(++index, Timestamp.from(to.toJavaInstant()))
                statement.setString(++index, afterId)
                statement.setString(++index, afterId)
                statement.setInt(++index, limit)
                statement.executeQuery().use { rs ->
                    ok(
                        buildList {
                            while (rs.next()) {
                                add(
                                    Row(
                                        rs.getString("id"),
                                        rs.getTimestamp("updated_at").toInstant().toKotlinInstant(),
                                        rs.getString("failure"),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }

    private companion object {
        const val SELECT =
            "SELECT o.id, o.updated_at, s.failure FROM orders o LEFT JOIN order_saga s ON s.order_id = o.id " +
                "WHERE o.ordered_at >= ? AND o.ordered_at < ? AND (?::varchar IS NULL OR o.id COLLATE \"C\" > ?) " +
                "ORDER BY o.id COLLATE \"C\" LIMIT ?"
    }
}
