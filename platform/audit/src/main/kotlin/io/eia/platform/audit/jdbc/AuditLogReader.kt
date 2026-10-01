package io.eia.platform.audit.jdbc

import io.eia.platform.audit.AuditError
import io.eia.platform.audit.AuditEventNormalizer
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
    ) {
        val position: Position get() = Position(seq, hash)
    }

    /** チェーンの中の位置(記録の `seq` と保存された `hash`)。差分の読み込み・検証の起点にする。 */
    public data class Position(
        val seq: Long,
        val hash: String,
    )

    public fun readHead(connection: Connection): Result<Head?, AuditError> = sqlCatching { ok(head(connection)) }

    private val SELECT_HEAD = "SELECT seq, hash, canonical_version FROM ${AuditSchema.TABLE} ORDER BY seq DESC LIMIT 1"

    internal fun head(connection: Connection): Head? =
        connection.prepareStatement(SELECT_HEAD).use { statement ->
            statement.executeQuery().use { rows ->
                // hash を NULL にする改竄でも例外にしない(空の値は検証で一致しない)
                if (rows.next()) Head(rows.getLong("seq"), rows.getString("hash").orEmpty(), rows.getInt("canonical_version")) else null
            }
        }

    /**
     * `(seq, hash)` の昇順に 1 ページ([pageSize] 件)ずつ読んで [consumer] に渡す(キーセットのページング。全件をメモリに載せない)。
     * 読み終えた件数を返す。
     *
     * ページングのキーを `seq` だけにすると、主キーを外して `seq` を重複させた行がページの境界で読み飛ばされるため、`hash` と組にする。
     * `seq` や `hash` を NULL にした行はここでは読めないので、[countRows] の件数と照合する(AuditVerification)。
     *
     * [after] を指定すると、`(seq, hash)` がその位置より後の行だけを読む(前回のアンカーからの差分。AnchorCycle)。
     */
    public fun forEachRow(
        connection: Connection,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        after: Position? = null,
        consumer: (StoredRow) -> Unit,
    ): Result<Long, AuditError> =
        sqlCatching {
            var afterSeq = after?.seq ?: Long.MIN_VALUE
            var afterHash = after?.hash ?: ""
            var count = 0L
            do {
                val page = page(connection, afterSeq, afterHash, pageSize)
                page.forEach { consumer(it.row) }
                count += page.size
                page.lastOrNull()?.let {
                    afterSeq = it.row.seq
                    afterHash = it.pageHash
                }
            } while (page.size == pageSize)
            ok(count)
        }

    /** 表の全件数(`seq` や `hash` が NULL の行も含む)。 */
    public fun countRows(connection: Connection): Result<Long, AuditError> =
        sqlCatching {
            connection.prepareStatement("SELECT count(*) FROM ${AuditSchema.TABLE}").use { statement ->
                statement.executeQuery().use { rows ->
                    rows.next()
                    ok(rows.getLong(1))
                }
            }
        }

    /** 読んだ行と、次のページの起点にする `hash` の値(保存された値そのもの)。 */
    private class PagedRow(
        val row: StoredRow,
        val pageHash: String,
    )

    private fun page(
        connection: Connection,
        afterSeq: Long,
        afterHash: String,
        pageSize: Int,
    ): List<PagedRow> =
        connection.prepareStatement("$SELECT WHERE (seq, hash) > (?, ?) ORDER BY seq, hash LIMIT ?").use { statement ->
            var index = 0
            statement.setLong(++index, afterSeq)
            statement.setString(++index, afterHash)
            statement.setInt(++index, pageSize)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(PagedRow(rows.toStoredRow(), rows.getString("hash").orEmpty()))
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
        // 'infinity' など、記録する側が受け付けない範囲の時刻(直列化でマイクロ秒に直せない)
        val outOfRange =
            AuditEventNormalizer.checkInstant("occurred_at", occurredAt.toInstant())
                ?: AuditEventNormalizer.checkInstant("recorded_at", recordedAt.toInstant())
        if (outOfRange != null) return malformed("${outOfRange.field} が記録できる範囲の外です")
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
