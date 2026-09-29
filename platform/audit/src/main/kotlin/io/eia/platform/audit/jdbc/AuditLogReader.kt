package io.eia.platform.audit.jdbc

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.AuditRecord
import io.eia.platform.audit.StoredRow
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime

/** 監査記録を読む。検証のため、列の値を解釈せず保存されたとおりに返す([AuditRecord])。 */
public object AuditLogReader {
    public const val DEFAULT_PAGE_SIZE: Int = 1_000

    /** チェーンの末尾(`seq` の最大の記録)。記録がなければ null。 */
    public data class Head(
        val seq: Long,
        val hash: String,
        val canonicalVersion: Int,
    )

    public fun readHead(connection: Connection): Result<Head?, AuditError> = sqlCatching { ok(head(connection)) }

    private val SELECT_HEAD = "SELECT seq, hash, canonical_version FROM ${AuditSchema.TABLE} ORDER BY seq DESC LIMIT 1"

    internal fun head(connection: Connection): Head? =
        connection.prepareStatement(SELECT_HEAD).use { statement ->
            statement.executeQuery().use { rows ->
                if (rows.next()) Head(rows.getLong("seq"), rows.getString("hash"), rows.getInt("canonical_version")) else null
            }
        }

    /**
     * `seq` の昇順に 1 ページ([pageSize] 件)ずつ読んで [consumer] に渡す(キーセットのページング。全件をメモリに載せない)。
     * 読み終えた件数を返す。
     */
    public fun forEachRow(
        connection: Connection,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        consumer: (StoredRow) -> Unit,
    ): Result<Long, AuditError> =
        sqlCatching {
            var after = Long.MIN_VALUE
            var count = 0L
            do {
                val page = page(connection, after, pageSize)
                page.forEach(consumer)
                count += page.size
                page.lastOrNull()?.let { after = it.seq }
            } while (page.size == pageSize)
            ok(count)
        }

    private fun page(
        connection: Connection,
        after: Long,
        pageSize: Int,
    ): List<StoredRow> =
        connection.prepareStatement("$SELECT WHERE seq > ? ORDER BY seq LIMIT ?").use { statement ->
            statement.setLong(1, after)
            statement.setInt(2, pageSize)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.toStoredRow())
                }
            }
        }

    @Suppress("ReturnCount") // 解釈できない列ごとに Malformed を返す
    private fun ResultSet.toStoredRow(): StoredRow {
        val seq = getLong("seq")
        // NOT NULL の制約を外して NULL にする改竄もありうるため、解釈できない行は Malformed にする(例外で検証を止めない)
        val hash: String? = getString("hash")
        val prevHash: String? = getString("prev_hash")
        val occurredAt: OffsetDateTime? = getObject("occurred_at", OffsetDateTime::class.java)
        val recordedAt: OffsetDateTime? = getObject("recorded_at", OffsetDateTime::class.java)

        fun malformed(reason: String) = StoredRow.Malformed(seq, hash.orEmpty(), prevHash.orEmpty(), reason)
        if (hash == null || prevHash == null) return malformed("hash か prev_hash が NULL です")
        if (occurredAt == null || recordedAt == null) return malformed("occurred_at か recorded_at が NULL です")
        val details =
            when (val parsed = parseDetails(getString("details"))) {
                is Result.Ok -> parsed.value
                is Result.Err -> return malformed(parsed.error)
            }
        return StoredRow.Parsed(
            AuditRecord(
                seq = seq,
                canonicalVersion = getInt("canonical_version"),
                occurredAt = occurredAt.toInstant(),
                recordedAt = recordedAt.toInstant(),
                actorType = getString("actor_type"),
                actorId = getString("actor_id"),
                action = getString("action"),
                targetType = getString("target_type"),
                targetId = getString("target_id"),
                destination = getString("destination"),
                outcome = getString("outcome"),
                payloadSha256 = getString("payload_sha256"),
                payloadRef = getString("payload_ref"),
                correlationId = getString("correlation_id"),
                traceparent = getString("traceparent"),
                details = details,
                prevHash = prevHash,
                hash = hash,
            ),
        )
    }

    /** details は文字列か null の値だけを持つ JSON のオブジェクト。それ以外(数値・入れ子など)は改竄の疑いとする。 */
    @Suppress("ReturnCount") // 形式の違反ごとに返す
    internal fun parseDetails(text: String?): Result<Map<String, String?>, String> {
        if (text == null) return Result.Err("details が NULL です")
        val element =
            try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                return Result.Err("details が JSON として解釈できません(${e::class.simpleName})")
            }
        if (element !is JsonObject) return Result.Err("details が JSON のオブジェクトではありません")
        return ok(
            element.mapValues { (key, value) ->
                when {
                    value is JsonNull -> null
                    value is JsonPrimitive && value.isString -> value.content
                    else -> return Result.Err("details の $key の値が文字列でも null でもありません")
                }
            },
        )
    }

    private val SELECT =
        """
        SELECT seq, canonical_version, occurred_at, recorded_at, actor_type, actor_id, action, target_type, target_id,
               destination, outcome, payload_sha256, payload_ref, correlation_id, traceparent, details::text AS details, prev_hash, hash
        FROM ${AuditSchema.TABLE}
        """.trimIndent()
}
