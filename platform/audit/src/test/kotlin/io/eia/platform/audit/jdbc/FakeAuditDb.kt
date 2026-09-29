package io.eia.platform.audit.jdbc

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime

/**
 * 単体テスト用の、メモリ上の監査テーブル。[AuditLog] と [AuditLogReader] が発行する SQL だけを解釈する
 * (実際の PostgreSQL での振る舞いは統合テスト AuditLogIT で確かめる)。
 *
 * 行は列名から値への Map で持つ。details は JSON の文字列。改竄のテストでは [rows] を直接書き換える。
 */
internal class FakeAuditDb(
    var isolation: String = "read committed",
) {
    val rows: MutableList<MutableMap<String, Any?>> = mutableListOf()
    var lockCount: Int = 0
        private set

    /** 設定すると、次の SQL の実行でこの SQLSTATE の例外を投げる。 */
    var failWith: String? = null

    fun connection(autoCommit: Boolean = false): Connection {
        var auto = autoCommit
        return proxy { method, args ->
            when (method) {
                "getAutoCommit" -> auto
                "setAutoCommit" -> auto = args[0] as Boolean
                "prepareStatement" -> statement(args[0] as String)
                "setReadOnly", "close", "commit", "rollback" -> Unit
                "isClosed" -> false
                else -> throw UnsupportedOperationException("Connection.$method")
            }
        }
    }

    private fun statement(sql: String): PreparedStatement {
        val params = sortedMapOf<Int, Any?>()
        return proxy { method, args ->
            when (method) {
                "setLong", "setInt", "setString", "setObject" -> params[args[0] as Int] = args[1]
                "executeQuery" -> query(sql, params.values.toList())
                "executeUpdate" -> update(sql, params.values.toList())
                "close" -> Unit
                else -> throw UnsupportedOperationException("PreparedStatement.$method")
            }
        }
    }

    private fun checkFailure() {
        failWith?.let {
            failWith = null
            throw SQLException("失敗(テスト)", it)
        }
    }

    private fun query(
        sql: String,
        params: List<Any?>,
    ): ResultSet {
        checkFailure()
        return when {
            sql.startsWith("SELECT current_setting") -> {
                resultSet(listOf(mapOf("1" to isolation)))
            }

            sql.startsWith("SELECT pg_advisory_xact_lock") -> {
                resultSet(listOf(mapOf("1" to null))).also { lockCount++ }
            }

            sql.contains("ORDER BY seq DESC LIMIT 1") -> {
                resultSet(listOfNotNull(rows.maxByOrNull { it["seq"] as Long }))
            }

            sql.contains("WHERE seq > ?") -> {
                val after = params[0] as Long
                val limit = params[1] as Int
                resultSet(rows.filter { (it["seq"] as Long) > after }.sortedBy { it["seq"] as Long }.take(limit))
            }

            else -> {
                throw UnsupportedOperationException(sql)
            }
        }
    }

    private fun update(
        sql: String,
        params: List<Any?>,
    ): Int {
        checkFailure()
        require(sql.startsWith("INSERT INTO ${AuditSchema.TABLE}")) { sql }
        val row = COLUMNS.zip(params).toMap().toMutableMap()
        if (rows.any { it["seq"] == row["seq"] }) throw SQLException("duplicate key", "23505")
        rows += row
        return 1
    }

    private fun resultSet(data: List<Map<String, Any?>>): ResultSet {
        var index = -1

        fun value(column: Any): Any? {
            val row = data[index]
            return if (column is Int) row.values.toList()[column - 1] else row[column as String]
        }
        return proxy { method, args ->
            when (method) {
                "next" -> ++index < data.size
                "getLong" -> value(args[0]!!) as Long
                "getInt" -> (value(args[0]!!) as Number?)?.toInt() ?: 0
                "getString" -> value(args[0]!!)?.toString()
                "getObject" -> value(args[0]!!) as OffsetDateTime?
                "close" -> Unit
                else -> throw UnsupportedOperationException("ResultSet.$method")
            }
        }
    }

    private inline fun <reified T> proxy(crossinline handler: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
            InvocationHandler { _, method, args ->
                handler(method.name, args ?: emptyArray())
            },
        ) as T

    companion object {
        /** AuditLog の INSERT の列の順序。 */
        val COLUMNS =
            listOf(
                "seq",
                "canonical_version",
                "occurred_at",
                "recorded_at",
                "actor_type",
                "actor_id",
                "action",
                "target_type",
                "target_id",
                "destination",
                "outcome",
                "payload_sha256",
                "payload_ref",
                "correlation_id",
                "traceparent",
                "details",
                "prev_hash",
                "hash",
            )
    }
}
