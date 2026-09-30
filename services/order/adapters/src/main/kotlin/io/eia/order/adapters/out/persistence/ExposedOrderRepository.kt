package io.eia.order.adapters.out.persistence

import io.eia.order.application.port.outbound.OrderRepository
import io.eia.order.application.port.outbound.OrderVersionConflict
import io.eia.order.domain.AddressDraft
import io.eia.order.domain.CustomerId
import io.eia.order.domain.Order
import io.eia.order.domain.OrderId
import io.eia.order.domain.OrderStatus
import io.eia.order.domain.ProductId
import io.eia.order.domain.RestoredLine
import io.eia.order.domain.ShippingAddress
import io.eia.order.domain.Sku
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.CurrencyResolver
import io.eia.shared.kernel.money.Money
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * [OrderRepository] の PostgreSQL の実装。SQL は PreparedStatement で書く(MODULE_DESIGN §3)。
 *
 * - 呼び出し元のトランザクションがあれば参加し、なければ 1 回の操作ごとにトランザクションを開く([ExposedJdbcSession])。
 * - **楽観的ロック**: [update] は `UPDATE orders ... WHERE id = ? AND version = ?` の更新件数で衝突を検出する。
 *   READ COMMITTED では、後から来た UPDATE は先の行ロックが解けるのを待ってから条件を評価し直すので、先に確定した更新の版を見て
 *   0 件になり、[OrderVersionConflict] を返す(docs/architecture/order-state-machine.md)。
 * - 更新できるのは状態だけ(注文の内容と明細は変えない。domain の規則)。
 * - 金額は最小通貨単位と通貨コードで保存し、読むときに [currencies] で通貨を戻す(ADR-0011)。
 * - 保存した値が domain の規則を満たさなければ(保存された値の破損)、[UnexpectedError] にする。理由には値を入れない。
 */
public class ExposedOrderRepository internal constructor(
    private val session: JdbcSession,
    private val currencies: CurrencyResolver,
) : OrderRepository {
    public constructor(database: Database, currencies: CurrencyResolver = CurrencyResolver.COMMON) :
        this(ExposedJdbcSession(database), currencies)

    override suspend fun insert(order: Order): Result<Unit, DomainError> =
        session.run { connection ->
            try {
                insertOrder(connection, order)
                insertLines(connection, order)
                ok(Unit)
            } catch (e: SQLException) {
                if (e.sqlState == SqlErrors.UNIQUE_VIOLATION) err(ConflictError("注文 ${order.id} は登録済みです")) else throw e
            }
        }

    override suspend fun findById(id: OrderId): Result<Order?, DomainError> =
        session.run { connection ->
            val row = readOrder(connection, id)
            if (row == null) ok(null) else restore(row, readLines(connection, id))
        }

    override suspend fun update(order: Order): Result<Order, DomainError> =
        session.run { connection ->
            val updated =
                connection.prepareStatement(UPDATE_STATUS).use { statement ->
                    var index = 0
                    statement.setString(++index, order.status.name)
                    statement.setString(++index, order.id.value)
                    statement.setLong(++index, order.version)
                    statement.executeUpdate()
                }
            when {
                updated == 1 -> ok(order.withVersion(order.version + 1))
                exists(connection, order.id) -> err(OrderVersionConflict(order.id, order.version))
                else -> err(NotFoundError("order", order.id.value))
            }
        }

    private fun insertOrder(
        connection: Connection,
        order: Order,
    ) {
        connection.prepareStatement(INSERT_ORDER).use { statement ->
            val address = order.shippingAddress
            var index = 0
            statement.setString(++index, order.id.value)
            statement.setString(++index, order.customerId.value)
            statement.setString(++index, order.status.name)
            statement.setTimestamp(++index, Timestamp.from(order.orderedAt.toJavaInstant()))
            statement.setString(++index, order.totalAmount.currency.code)
            statement.setLong(++index, order.totalAmount.minorUnits)
            listOf(address.countryCode, address.postalCode, address.region, address.city, address.line1, address.line2)
                .forEach { statement.setString(++index, it) }
            statement.setLong(++index, order.version)
            statement.executeUpdate()
        }
    }

    private fun insertLines(
        connection: Connection,
        order: Order,
    ) {
        connection.prepareStatement(INSERT_LINE).use { statement ->
            order.lines.forEach { line ->
                var index = 0
                statement.setString(++index, order.id.value)
                statement.setInt(++index, line.lineNumber)
                statement.setString(++index, line.productId.value)
                statement.setString(++index, line.sku.value)
                statement.setLong(++index, line.quantity)
                statement.setLong(++index, line.unitPrice.minorUnits)
                statement.setLong(++index, line.lineAmount.minorUnits)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun readOrder(
        connection: Connection,
        id: OrderId,
    ): OrderRow? =
        connection.prepareStatement(SELECT_ORDER).use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { if (it.next()) OrderRow.read(it) else null }
        }

    private fun readLines(
        connection: Connection,
        id: OrderId,
    ): List<LineRow> =
        connection.prepareStatement(SELECT_LINES).use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { rs -> generateSequence { if (rs.next()) LineRow.read(rs) else null }.toList() }
        }

    private fun exists(
        connection: Connection,
        id: OrderId,
    ): Boolean =
        connection.prepareStatement(EXISTS).use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { it.next() }
        }

    private fun restore(
        row: OrderRow,
        lines: List<LineRow>,
    ): Result<Order?, DomainError> =
        try {
            ok(row.toOrder(currencies, lines))
        } catch (e: InvalidStoredValue) {
            err(UnexpectedError("保存された注文の値が不正です(${e.field})"))
        }

    /** 保存された値が domain の規則を満たさない(値は持たず、項目の名前だけを持つ)。 */
    private class InvalidStoredValue(
        val field: String,
    ) : RuntimeException(null, null, false, false)

    @Suppress("LongParameterList") // orders の列
    private class OrderRow(
        val id: String,
        val customerId: String,
        val status: String,
        val orderedAt: Timestamp,
        val totalAmount: Pair<String, Long>,
        val address: AddressDraft,
        val version: Long,
    ) {
        fun toOrder(
            currencies: CurrencyResolver,
            lines: List<LineRow>,
        ): Order {
            val currency = currencies.resolve(totalAmount.first) ?: throw InvalidStoredValue("currency")
            return Order.restore(
                OrderId.parse(id).orInvalid("id"),
                CustomerId.parse(customerId).orInvalid("customer_id"),
                OrderStatus.entries.firstOrNull { it.name == status } ?: throw InvalidStoredValue("status"),
                orderedAt.toInstant().toKotlinInstant(),
                lines.map { it.toLine(currency) },
                Money.ofMinor(totalAmount.second, currency),
                ShippingAddress.of(address).orInvalid("ship_*"),
                version,
            )
        }

        companion object {
            fun read(rs: ResultSet): OrderRow =
                OrderRow(
                    id = rs.getString("id"),
                    customerId = rs.getString("customer_id"),
                    status = rs.getString("status"),
                    orderedAt = rs.getTimestamp("ordered_at"),
                    totalAmount = rs.getString("currency") to rs.getLong("total_amount_minor"),
                    address =
                        AddressDraft(
                            countryCode = rs.getString("ship_country_code"),
                            postalCode = rs.getString("ship_postal_code"),
                            region = rs.getString("ship_region"),
                            city = rs.getString("ship_city"),
                            line1 = rs.getString("ship_line1"),
                            line2 = rs.getString("ship_line2"),
                        ),
                    version = rs.getLong("version"),
                )
        }
    }

    private class LineRow(
        val lineNumber: Int,
        val productId: String,
        val sku: String,
        val quantity: Long,
        val amounts: Pair<Long, Long>,
    ) {
        fun toLine(currency: Currency): RestoredLine =
            RestoredLine(
                lineNumber,
                ProductId.parse(productId).orInvalid("product_id"),
                Sku.parse(sku).orInvalid("sku"),
                quantity,
                Money.ofMinor(amounts.first, currency),
                Money.ofMinor(amounts.second, currency),
            )

        companion object {
            fun read(rs: ResultSet): LineRow =
                LineRow(
                    lineNumber = rs.getInt("line_number"),
                    productId = rs.getString("product_id"),
                    sku = rs.getString("sku"),
                    quantity = rs.getLong("quantity"),
                    amounts = rs.getLong("unit_price_minor") to rs.getLong("line_amount_minor"),
                )
        }
    }

    private companion object {
        const val INSERT_ORDER =
            "INSERT INTO orders (id, customer_id, status, ordered_at, currency, total_amount_minor, ship_country_code, " +
                "ship_postal_code, ship_region, ship_city, ship_line1, ship_line2, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        const val INSERT_LINE =
            "INSERT INTO order_lines (order_id, line_number, product_id, sku, quantity, unit_price_minor, line_amount_minor) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"
        const val SELECT_ORDER =
            "SELECT id, customer_id, status, ordered_at, currency, total_amount_minor, ship_country_code, ship_postal_code, " +
                "ship_region, ship_city, ship_line1, ship_line2, version FROM orders WHERE id = ?"
        const val SELECT_LINES =
            "SELECT line_number, product_id, sku, quantity, unit_price_minor, line_amount_minor FROM order_lines " +
                "WHERE order_id = ? ORDER BY line_number"
        const val UPDATE_STATUS = "UPDATE orders SET status = ?, version = version + 1, updated_at = now() WHERE id = ? AND version = ?"
        const val EXISTS = "SELECT 1 FROM orders WHERE id = ?"

        /** 検証を通らない保存された値を、[InvalidStoredValue] にする。 */
        fun <T> Result<T, DomainError>.orInvalid(field: String): T =
            when (this) {
                is Result.Ok -> value
                is Result.Err -> throw InvalidStoredValue(field)
            }
    }
}

/** 版だけを替えた注文(保存した値からの復元と同じ経路で作る)。 */
internal fun Order.withVersion(version: Long): Order =
    Order.restore(
        id,
        customerId,
        status,
        orderedAt,
        lines.map { RestoredLine(it.lineNumber, it.productId, it.sku, it.quantity, it.unitPrice, it.lineAmount) },
        totalAmount,
        shippingAddress,
        version,
    )
