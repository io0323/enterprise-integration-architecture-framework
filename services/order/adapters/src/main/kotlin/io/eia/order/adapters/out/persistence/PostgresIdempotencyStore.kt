package io.eia.order.adapters.out.persistence

import io.eia.platform.api.idempotency.ClaimResult
import io.eia.platform.api.idempotency.IdempotencyRequest
import io.eia.platform.api.idempotency.IdempotencyStore
import io.eia.platform.api.idempotency.Lease
import io.eia.platform.api.idempotency.RequestFingerprint
import io.eia.platform.api.idempotency.StoredResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * [IdempotencyStore] の PostgreSQL の実装(表 `idempotency_record`。ADR-0022 §3)。SQL は PreparedStatement で書く(MODULE_DESIGN §3)。
 *
 * - **時刻は DB の `clock_timestamp()`** でリースの期限・保持期限を設定し、判定する。アプリの時計は使わない。複数のインスタンスの
 *   時計のずれで、有効なリースを横取りしたり、期限の前の記録を消したりしないため。`now()` はトランザクションの開始の時刻で、
 *   長い業務のトランザクションの中の [complete] では古くなるため、`clock_timestamp()`(文の実行の時刻)を使う。
 * - [claim] は `INSERT ... ON CONFLICT DO UPDATE ... WHERE ... RETURNING` の 1 文で原子的に受け付ける。同じキーが同時に来ても、
 *   衝突した行は ON CONFLICT の行ロックで直列になり、処理中になるのは 1 つだけ。引き継げない既存の記録は、読んで返す。
 *   [claim] は自分のトランザクションで確定させる(ほかの要求にすぐ処理中が見えるように)。
 * - [complete] は呼び出し元のトランザクションの中でだけ実行する(業務の更新と一緒に確定・取り消し)。トランザクションがなければ例外。
 * - [complete] と [release] はトークンが一致する処理中の行だけを変える(フェンシング)。
 * - DB の失敗は例外のまま伝える(Port は `Result` を返さない。[io.eia.platform.api.idempotency.IdempotencyHandler] が 500 にする)。
 */
public class PostgresIdempotencyStore internal constructor(
    private val connections: IdempotencyConnections,
) : IdempotencyStore {
    public constructor(database: Database) : this(ExposedIdempotencyConnections(database))

    override suspend fun claim(
        request: IdempotencyRequest,
        lease: Duration,
    ): ClaimResult {
        val token = UUID.randomUUID().toString()
        repeat(MAX_CLAIM_ATTEMPTS) {
            val result =
                connections.newTransaction { connection ->
                    if (tryAcquire(connection, request, token, lease)) {
                        ClaimResult.Acquired(Lease(request.scope, token))
                    } else {
                        existing(connection, request)
                    }
                }
            // 既存の記録を読む前に、期限切れの削除で消えていたら受け付けからやり直す
            if (result != null) return result
        }
        error("冪等の記録を受け付けられません(同じキーの記録の作成と削除が繰り返されています)")
    }

    override suspend fun complete(
        lease: Lease,
        response: StoredResponse,
        retention: Duration,
    ): Boolean =
        connections.currentTransaction { connection ->
            connection.prepareStatement(COMPLETE).use { statement ->
                var index = 0
                statement.setInt(++index, response.status)
                statement.setString(++index, headersJson(response.headers))
                statement.setBytes(++index, response.body)
                statement.setLong(++index, retention.inWholeMilliseconds)
                bindLease(statement, index, lease)
                statement.executeUpdate() == 1
            }
        }

    override suspend fun release(lease: Lease) {
        connections.joinOrNewTransaction { connection ->
            connection.prepareStatement(RELEASE).use { statement ->
                bindLease(statement, 0, lease)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun purgeExpired(inProgressGrace: Duration): Int =
        connections.newTransaction { connection ->
            connection.prepareStatement(PURGE).use { statement ->
                statement.setLong(1, inProgressGrace.inWholeMilliseconds)
                statement.executeUpdate()
            }
        }

    private fun tryAcquire(
        connection: Connection,
        request: IdempotencyRequest,
        token: String,
        lease: Duration,
    ): Boolean =
        connection.prepareStatement(CLAIM).use { statement ->
            var index = 0
            statement.setString(++index, request.scope.clientId)
            statement.setString(++index, request.scope.key.value)
            statement.setString(++index, request.fingerprint.value)
            statement.setString(++index, token)
            statement.setLong(++index, lease.inWholeMilliseconds)
            statement.executeQuery().use { it.next() && it.getString("lease_token") == token }
        }

    /** 引き継げなかった既存の記録。読む前に消えていたら `null`。 */
    private fun existing(
        connection: Connection,
        request: IdempotencyRequest,
    ): ClaimResult? =
        connection.prepareStatement(SELECT_EXISTING).use { statement ->
            statement.setString(1, request.scope.clientId)
            statement.setString(2, request.scope.key.value)
            statement.executeQuery().use { rs -> if (rs.next()) toClaimResult(rs) else null }
        }

    private fun toClaimResult(rs: ResultSet): ClaimResult {
        val fingerprint = RequestFingerprint(rs.getString("fingerprint"))
        return if (rs.getString("state") == COMPLETED) {
            ClaimResult.Completed(
                fingerprint,
                StoredResponse(rs.getInt("response_status"), parseHeaders(rs.getString("response_headers")), rs.getBytes("response_body")),
            )
        } else {
            ClaimResult.InProgress(fingerprint, rs.getLong("lease_remaining_ms").milliseconds)
        }
    }

    private fun bindLease(
        statement: PreparedStatement,
        offset: Int,
        lease: Lease,
    ) {
        var index = offset
        statement.setString(++index, lease.scope.clientId)
        statement.setString(++index, lease.scope.key.value)
        statement.setString(++index, lease.token)
    }

    private companion object {
        const val MAX_CLAIM_ATTEMPTS = 3
        const val COMPLETED = "COMPLETED"

        /** 保存するヘッダ。順序と重複を保つため、[名前, 値] の配列の配列にする。 */
        fun headersJson(headers: List<Pair<String, String>>): String =
            JsonArray(headers.map { (name, value) -> JsonArray(listOf(JsonPrimitive(name), JsonPrimitive(value))) }).toString()

        fun parseHeaders(json: String): List<Pair<String, String>> =
            Json.parseToJsonElement(json).jsonArray.map { pair ->
                val (name, value) = pair.jsonArray
                name.jsonPrimitive.content to value.jsonPrimitive.content
            }

        const val CLAIM =
            """
            INSERT INTO idempotency_record (client_id, idem_key, fingerprint, state, lease_token, lease_expires_at)
            VALUES (?, ?, ?, 'IN_PROGRESS', ?, clock_timestamp() + ? * interval '1 millisecond')
            ON CONFLICT (client_id, idem_key) DO UPDATE SET
                fingerprint = EXCLUDED.fingerprint, state = 'IN_PROGRESS',
                lease_token = EXCLUDED.lease_token, lease_expires_at = EXCLUDED.lease_expires_at,
                response_status = NULL, response_headers = NULL, response_body = NULL, expires_at = NULL
            WHERE (idempotency_record.state = 'COMPLETED' AND idempotency_record.expires_at <= clock_timestamp())
               OR (idempotency_record.state = 'IN_PROGRESS' AND idempotency_record.lease_expires_at <= clock_timestamp()
                   AND idempotency_record.fingerprint = EXCLUDED.fingerprint)
            RETURNING lease_token
            """

        const val SELECT_EXISTING =
            """
            SELECT state, fingerprint, response_status, response_headers, response_body,
                   GREATEST(0, CEIL(EXTRACT(EPOCH FROM (lease_expires_at - clock_timestamp())) * 1000))::bigint AS lease_remaining_ms
            FROM idempotency_record WHERE client_id = ? AND idem_key = ?
            """

        const val COMPLETE =
            """
            UPDATE idempotency_record SET state = 'COMPLETED', response_status = ?, response_headers = ?::jsonb, response_body = ?,
                expires_at = clock_timestamp() + ? * interval '1 millisecond', lease_token = NULL, lease_expires_at = NULL
            WHERE client_id = ? AND idem_key = ? AND state = 'IN_PROGRESS' AND lease_token = ?
            """

        const val RELEASE =
            "DELETE FROM idempotency_record WHERE client_id = ? AND idem_key = ? AND state = 'IN_PROGRESS' AND lease_token = ?"

        const val PURGE =
            """
            DELETE FROM idempotency_record
            WHERE (state = 'COMPLETED' AND expires_at <= clock_timestamp())
               OR (state = 'IN_PROGRESS' AND lease_expires_at + ? * interval '1 millisecond' <= clock_timestamp())
            """
    }
}

/** 冪等の保存先が使うトランザクションの 3 つの使い方。SQL と行の写し方を、DB なしで単体テストできるように分けた。 */
internal interface IdempotencyConnections {
    /** 新しいトランザクションで実行して確定する(呼び出し元のトランザクションの中では呼ばせない)。 */
    suspend fun <T> newTransaction(block: (Connection) -> T): T

    /** 呼び出し元のトランザクションがあれば参加し、なければ新しいトランザクションで実行する。 */
    suspend fun <T> joinOrNewTransaction(block: (Connection) -> T): T

    /** 呼び出し元のトランザクションの中でだけ実行する(なければ使い方の誤りとして例外)。 */
    suspend fun <T> currentTransaction(block: (Connection) -> T): T
}

internal class ExposedIdempotencyConnections(
    private val database: Database,
) : IdempotencyConnections {
    override suspend fun <T> newTransaction(block: (Connection) -> T): T {
        check(database.currentTransaction() == null) { "この操作は、業務のトランザクションの外で呼んでください(すぐに確定させるため)" }
        return withContext(Dispatchers.IO) { suspendTransaction(database) { block(jdbcConnection()) } }
    }

    override suspend fun <T> joinOrNewTransaction(block: (Connection) -> T): T {
        val current = database.currentTransaction()
        return if (current != null) {
            block(current.jdbcConnection())
        } else {
            withContext(Dispatchers.IO) { suspendTransaction(database) { block(jdbcConnection()) } }
        }
    }

    override suspend fun <T> currentTransaction(block: (Connection) -> T): T {
        val current =
            checkNotNull(database.currentTransaction()) {
                "IdempotencyStore.complete は業務の更新と同じトランザクションの中で呼んでください(ADR-0022 §3)"
            }
        return block(current.jdbcConnection())
    }
}
