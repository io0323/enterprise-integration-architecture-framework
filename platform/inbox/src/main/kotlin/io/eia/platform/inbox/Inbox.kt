package io.eia.platform.inbox

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * 冪等消費の記録(processed_message。Framework 6.4・13.2。ADR-0028 §3)。
 *
 * At-Least-Once では同じメッセージが 2 回以上届く(コミットの前に落ちた・Replay・オフセットの巻き戻し)。
 * 受信側は、業務の更新と **同じトランザクション** で [markProcessed] を呼び、[Receipt.DUPLICATE] なら業務の処理をせずに終える。
 * 業務がロールバックすれば記録も残らないので、次の受信でやり直せる。
 *
 * - キーは Consumer Group とメッセージの ID(`ce_id`)。同じメッセージでも、グループごとに 1 回ずつ処理する。
 * - 時刻は DB の時計(`clock_timestamp()`)。保持期間を過ぎた行は [purgeExpired] で消す(サービスが定期的に呼ぶ)。
 *
 * Exposed のトランザクションからは [markProcessedIn] を使う。
 */
public class Inbox {
    /** 受信の判定。 */
    public enum class Receipt {
        /** 初めての受信。業務の処理をする。 */
        FIRST,

        /** 処理済み。業務の処理をせず、成功として扱う(オフセットを進める)。 */
        DUPLICATE,
    }

    /**
     * [messageId] を [consumerGroup] で処理したことを記録する。
     *
     * 前提: [connection] は自動コミットが無効(業務の更新と同じトランザクションの中)であること。自動コミットでは
     * 記録だけが先に確定し、業務が失敗したときに処理されないまま重複として捨てられるため、[InboxMisuse] にする。
     */
    @Suppress("ReturnCount") // 引数の検査のそれぞれで、SQL の前に返す
    public fun markProcessed(
        connection: Connection,
        consumerGroup: String,
        messageId: Uuid,
        topic: String,
    ): Result<Receipt, InboxError> {
        if (!CONSUMER_GROUP.matches(consumerGroup)) return err(InboxMisuse("consumerGroup は {service}.{purpose} の形にしてください"))
        if (topic.isBlank()) return err(InboxMisuse("topic が空です"))
        return try {
            if (connection.autoCommit) {
                err(InboxMisuse("冪等消費の記録は業務の更新と同じトランザクションで書いてください(自動コミットが有効です)"))
            } else {
                val inserted =
                    connection.prepareStatement(INSERT).use { statement ->
                        statement.setString(1, consumerGroup)
                        statement.setObject(2, UUID.fromString(messageId.toString()))
                        statement.setString(3, topic)
                        statement.executeUpdate()
                    }
                ok(if (inserted == 1) Receipt.FIRST else Receipt.DUPLICATE)
            }
        } catch (e: SQLException) {
            err(classify(e))
        }
    }

    /**
     * DB の時計で [retention] より前に記録した行を、古い順に最大 [batchSize] 件消す。消した件数を返す。
     * 自動コミットのままでも、トランザクションの中でもよい。全件を消すには、0 が返るまで繰り返す(長いロックを避ける)。
     *
     * [retention] は、同じメッセージが再び届きうる期間より長くする(ADR-0028 §3: トピックと DLQ の保持期間の長い方 + 余裕)。
     */
    public fun purgeExpired(
        connection: Connection,
        retention: Duration = DEFAULT_RETENTION,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): Result<Int, InboxError> {
        require(retention.isPositive()) { "retention は正の値にしてください: $retention" }
        require(batchSize >= 1) { "batchSize は 1 以上にしてください: $batchSize" }
        return try {
            ok(
                connection.prepareStatement(PURGE).use { statement ->
                    statement.setLong(1, retention.inWholeSeconds)
                    statement.setInt(2, batchSize)
                    statement.executeUpdate()
                },
            )
        } catch (e: SQLException) {
            err(classify(e))
        }
    }

    public companion object {
        /**
         * 既定の保持期間(ADR-0028 §3)。業務イベント・コマンドのトピックと DLQ の保持期間(7 日。Framework 6.2・INTEGRATION_STANDARDS §2)の
         * 長い方に、DLQ から Replay した後に同じ DLQ をもう一度 Replay する場合の余裕(7 日)を足す。
         */
        public val DEFAULT_RETENTION: Duration = 14.days
        public const val DEFAULT_BATCH_SIZE: Int = 1000

        private val CONSUMER_GROUP = Regex("^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*$")
        private val INSERT =
            """
            INSERT INTO ${InboxSchema.TABLE} (consumer_group, message_id, topic)
            VALUES (?, ?, ?)
            ON CONFLICT (consumer_group, message_id) DO NOTHING
            """.trimIndent()
        private val PURGE =
            """
            DELETE FROM ${InboxSchema.TABLE}
            WHERE (consumer_group, message_id) IN (
                SELECT consumer_group, message_id FROM ${InboxSchema.TABLE}
                WHERE processed_at < clock_timestamp() - make_interval(secs => ?)
                ORDER BY processed_at
                LIMIT ?
            )
            """.trimIndent()
        private val TRANSIENT_CLASSES = setOf("08", "40", "53", "57")

        /** SQLSTATE のクラスで分類する(platform/outbox と同じ規則)。理由には SQLSTATE だけを入れる。 */
        internal fun classify(e: SQLException): InboxError {
            val state = e.sqlState.orEmpty()
            val reason = "SQLSTATE ${state.ifEmpty { "なし" }}"
            return if (state.isEmpty() || state.take(2) in TRANSIENT_CLASSES) {
                InboxStorageUnavailable(reason)
            } else {
                InboxStorageRejected(reason)
            }
        }
    }
}

/**
 * Exposed のトランザクションの中で記録する。業務の更新と同じトランザクションに入る。
 *
 * ```
 * transaction(database) {
 *     when (inbox.markProcessedIn(this, "inventory.command", event.id, event.topic)) {
 *         Inbox.Receipt.DUPLICATE -> return@transaction   // 業務の処理をしない
 *         Inbox.Receipt.FIRST -> reserve(...)
 *     }
 * }
 * ```
 */
public fun Inbox.markProcessedIn(
    transaction: JdbcTransaction,
    consumerGroup: String,
    messageId: Uuid,
    topic: String,
): Result<Inbox.Receipt, InboxError> {
    val connection = transaction.connection.connection as? Connection ?: return err(InboxMisuse("JDBC の接続を取得できません"))
    return markProcessed(connection, consumerGroup, messageId, topic)
}
