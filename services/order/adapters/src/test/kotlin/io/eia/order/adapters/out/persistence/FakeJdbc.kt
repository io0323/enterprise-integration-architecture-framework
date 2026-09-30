package io.eia.order.adapters.out.persistence

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp

/**
 * 単体テスト用の JDBC の代わり(MockK)。実行した SQL とパラメータを記録し、[rows] の行を返す。
 * 実際の PostgreSQL での振る舞い(制約・ロック・権限)は統合テストで確かめる。
 */
internal class FakeJdbc {
    data class Executed(
        val sql: String,
        val params: Map<Int, Any?>,
    )

    val executed = mutableListOf<Executed>()

    /** SQL(の先頭)とパラメータごとの、SELECT(と RETURNING)が返す行。 */
    var rows: (String, Map<Int, Any?>) -> List<Map<String, Any?>> = { _, _ -> emptyList() }

    /** UPDATE / INSERT の更新件数。 */
    var updated: (String) -> Int = { 1 }

    /** 設定すると、その SQL の実行で例外を投げる。 */
    var failOn: (String) -> SQLException? = { null }

    val connection: Connection = mockk { every { prepareStatement(any<String>()) } answers { statement(firstArg()) } }

    /** [connection] で実行し、SQL の例外を分類する(本番の ExposedJdbcSession と同じ分類)。 */
    val session: JdbcSession =
        object : JdbcSession {
            override suspend fun <T> run(block: (Connection) -> Result<T, DomainError>): Result<T, DomainError> =
                classifyingSqlErrors { block(connection) }
        }

    private fun statement(sql: String): PreparedStatement {
        val params = mutableMapOf<Int, Any?>()
        val batches = mutableListOf<Map<Int, Any?>>()

        fun fail() = failOn(sql)?.let { throw it }
        return mockk(relaxed = true) {
            every { setString(any(), any()) } answers { params[firstArg()] = secondArg<String?>() }
            every { setLong(any(), any()) } answers { params[firstArg()] = secondArg<Long>() }
            every { setInt(any(), any()) } answers { params[firstArg()] = secondArg<Int>() }
            every { setTimestamp(any(), any()) } answers { params[firstArg()] = secondArg<Timestamp>() }
            every { setBytes(any(), any()) } answers { params[firstArg()] = secondArg<ByteArray>() }
            every { addBatch() } answers { batches += params.toMap() }
            every { executeBatch() } answers {
                fail()
                batches.forEach { executed += Executed(sql, it) }
                IntArray(batches.size) { 1 }
            }
            every { executeUpdate() } answers {
                fail()
                executed += Executed(sql, params.toMap())
                updated(sql)
            }
            every { executeQuery() } answers {
                fail()
                executed += Executed(sql, params.toMap())
                resultSet(rows(sql, params.toMap()))
            }
        }
    }

    private fun resultSet(rows: List<Map<String, Any?>>): ResultSet {
        var index = -1
        return mockk(relaxed = true) {
            every { next() } answers { ++index < rows.size }
            every { getString(any<String>()) } answers { rows[index][firstArg()] as String? }
            every { getLong(any<String>()) } answers { (rows[index][firstArg()] as Number).toLong() }
            every { getInt(any<String>()) } answers { (rows[index][firstArg()] as Number).toInt() }
            every { getTimestamp(any<String>()) } answers { rows[index][firstArg()] as Timestamp }
            every { getBytes(any<String>()) } answers { rows[index][firstArg()] as ByteArray }
        }
    }
}
