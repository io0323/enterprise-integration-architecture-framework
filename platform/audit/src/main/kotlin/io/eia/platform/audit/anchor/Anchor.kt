package io.eia.platform.audit.anchor

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** アンカーのキーに使うサービス名(例 `order`)。 */
@JvmInline
public value class ServiceName private constructor(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        private val FORMAT = Regex("^[a-z][a-z0-9-]{0,62}$")

        public fun parse(value: String): Result<ServiceName, ValidationError> =
            if (FORMAT.matches(value)) ok(ServiceName(value)) else err(ValidationError.of("service", "英小文字で始まる英小文字・数字・- の 63 文字以内にしてください"))
    }
}

/**
 * チェーンの先頭(ある時点の末尾の記録)を S3 互換ストレージに保存したもの(ADR-0017)。
 * 保存した後に DB の記録を書き換え・削除しても、アンカーの `seq` と `hash` との照合で検出できる。
 */
@Serializable
public data class Anchor(
    val format: String = FORMAT,
    val service: String,
    val seq: Long,
    val hash: String,
    @SerialName("canonical_version")
    val canonicalVersion: Int,
    /** アンカーを作った時刻(ISO 8601、UTC)。キーの日付はこの時刻の UTC の日付。 */
    @SerialName("created_at")
    val createdAt: String,
) {
    public fun toJson(): ByteArray = JSON.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    public companion object {
        public const val FORMAT: String = "eiaf.audit.anchor.v1"
        private const val MAX_BYTES = 4 * 1024
        private val JSON = Json { encodeDefaults = true }

        @Suppress("ReturnCount") // 大きさ・JSON・形式のそれぞれの違反で返す
        public fun parse(bytes: ByteArray): Result<Anchor, String> {
            if (bytes.size > MAX_BYTES) return err("アンカーが $MAX_BYTES バイトを超えています")
            val anchor =
                try {
                    JSON.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
                } catch (e: SerializationException) {
                    return err("アンカーの JSON を解釈できません(${e::class.simpleName})")
                } catch (e: IllegalArgumentException) {
                    return err("アンカーの JSON を解釈できません(${e::class.simpleName})")
                }
            return if (anchor.format == FORMAT) ok(anchor) else err("アンカーの形式が $FORMAT ではありません")
        }
    }
}

/** `anchors/{service}/{date}.json`。date は UTC の日付(yyyy-MM-dd)。 */
public object AnchorKeys {
    public const val ROOT: String = "anchors"

    public fun prefix(service: ServiceName): String = "$ROOT/$service/"

    public fun of(
        service: ServiceName,
        createdAt: Instant,
    ): String = "${prefix(service)}${LocalDate.ofInstant(createdAt, ZoneOffset.UTC)}.json"
}
