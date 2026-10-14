package io.eia.platform.batch.file

import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * ファイルのマニフェスト(`.manifest.json`。`contracts/files/manifest.v1.schema.json`。Framework 9.2・INTEGRATION_STANDARDS §2)。
 *
 * ここでは形式だけを確かめる。件数と SHA-256 の照合は、データファイルを読むステップが行う(P09 の取り込み)。
 */
public data class FileManifest(
    val file: String,
    val recordCount: Long,
    val sha256: String,
    val schemaVersion: String,
    val createdAt: Instant,
    val traceparent: String,
    val correlationId: String,
    val sizeBytes: Long?,
) {
    public companion object {
        /** マニフェストの大きさの上限(これより大きいものは読まない)。 */
        public const val MAX_BYTES: Int = 64 * 1024
        public const val SUFFIX: String = ".manifest.json"

        private val json = Json { ignoreUnknownKeys = true }
        private val FILE = Regex("^[a-z][a-z0-9-]*_[a-z][a-z0-9-]*_[0-9]{14}_[0-9]{3,}\\.[a-z0-9]+(\\.[a-z0-9]+)?$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val SCHEMA_VERSION = Regex("^[a-z][a-z0-9-]*_[a-z][a-z0-9-]*\\.v[1-9][0-9]*$")
        private val TRACEPARENT = Regex("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")
        private const val MAX_CORRELATION_ID = 128

        /** マニフェストを読む。形式の誤りは項目の名前と理由だけを返す(値は入れない)。 */
        @Suppress("ReturnCount") // JSON として読めない・必須の項目がないときは、項目の検査の前に返す
        public fun parse(bytes: ByteArray): Result<FileManifest, ValidationError> {
            if (bytes.size > MAX_BYTES) return err(ValidationError.of("manifest", "$MAX_BYTES バイトを超えています"))
            val raw =
                try {
                    json.decodeFromString(ManifestJson.serializer(), bytes.decodeToString())
                } catch (_: SerializationException) {
                    return err(ValidationError.of("manifest", "JSON の形式か必須の項目が誤っています"))
                } catch (_: IllegalArgumentException) {
                    return err(ValidationError.of("manifest", "JSON の形式か必須の項目が誤っています"))
                }
            val violations = mutableListOf<FieldViolation>()

            fun check(
                ok: Boolean,
                field: String,
                reason: String,
            ) {
                if (!ok) violations += FieldViolation(field, reason)
            }
            check(FILE.matches(raw.file), "file", "{system}_{dataset}_{yyyyMMddHHmmss}_{seq}.{ext} の形にしてください")
            check(raw.recordCount >= 0, "recordCount", "0 以上にしてください")
            check(SHA256.matches(raw.sha256), "sha256", "小文字 16 進の 64 文字にしてください")
            check(SCHEMA_VERSION.matches(raw.schemaVersion), "schemaVersion", "{system}_{dataset}.v{n} の形にしてください")
            val createdAt = parseUtc(raw.createdAt)
            check(createdAt != null, "createdAt", "UTC(末尾 Z)の日時にしてください")
            check(TRACEPARENT.matches(raw.traceparent), "traceparent", "W3C Trace Context の形にしてください")
            check(raw.correlationId.length in 1..MAX_CORRELATION_ID, "correlationId", "1〜$MAX_CORRELATION_ID 文字にしてください")
            check(raw.sizeBytes == null || raw.sizeBytes >= 0, "sizeBytes", "0 以上にしてください")
            if (violations.isNotEmpty() || createdAt == null) return err(ValidationError(violations))
            return ok(
                FileManifest(
                    raw.file,
                    raw.recordCount,
                    raw.sha256,
                    raw.schemaVersion,
                    createdAt,
                    raw.traceparent,
                    raw.correlationId,
                    raw.sizeBytes,
                ),
            )
        }

        private fun parseUtc(value: String): Instant? {
            if (!value.endsWith("Z")) return null
            return try {
                Instant.parse(value)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

@Serializable
@Suppress("LongParameterList") // 契約(manifest.v1)の項目そのまま
private class ManifestJson(
    val file: String,
    val recordCount: Long,
    val sha256: String,
    val schemaVersion: String,
    val createdAt: String,
    val traceparent: String,
    val correlationId: String,
    val sizeBytes: Long? = null,
)
