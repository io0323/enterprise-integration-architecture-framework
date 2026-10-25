package io.eia.iotbridge.adapters.inbound

import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.eia.iotbridge.domain.DevicePayload
import io.eia.iotbridge.domain.Reading
import io.eia.iotbridge.domain.RejectionReason
import io.eia.iotbridge.domain.TelemetryRejection
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.time.Instant

/**
 * デバイスのペイロード(JSON)を、形式の版(`v`)のスキーマ(contracts/devices/telemetry.v{n}.schema.json。リソースに含めたもの)で
 * 検証してから [DevicePayload] にする(ADR-0039 §4)。未知の項目は無視する(Tolerant Reader)。
 *
 * - 知らない版(リソースにスキーマがない版)は `UNSUPPORTED_PAYLOAD_VERSION`、JSON として読めない・スキーマに合わないものは
 *   `INVALID_PAYLOAD`。どちらも捨てずに DLQ に隔離する(呼び出し元)。
 * - 誤りの説明には、項目の位置と破った規則(JSON Schema のキーワード)だけを入れ、**値は入れない**。
 *
 * @param versions 受け付ける形式の版。既定はリソースにあるすべての版
 */
public class DevicePayloadDecoder(
    versions: Set<Int> = SUPPORTED_VERSIONS,
) {
    private val schemas: Map<Int, Schema> = versions.associateWith(::loadSchema)

    @Suppress("ReturnCount") // 検査の段ごとに、最初の誤りで返す
    public fun decode(bytes: ByteArray): Result<DevicePayload, TelemetryRejection> {
        if (bytes.size > MAX_PAYLOAD_BYTES) return invalid("payload", "maxLength")
        val node =
            try {
                mapper.readTree(bytes)
            } catch (_: JacksonException) {
                return invalid("payload", "json")
            }
        if (!node.isObject) return invalid("payload", "type")
        val version = node.get("v")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: return invalid("v", "type")
        val schema = schemas[version] ?: return err(TelemetryRejection(RejectionReason.UNSUPPORTED_PAYLOAD_VERSION, "v"))
        val errors = schema.validate(node)
        if (errors.isNotEmpty()) {
            val detail =
                errors
                    .map { "${it.instanceLocation}:${it.keyword}" }
                    .distinct()
                    .sorted()
                    .joinToString(",")
            return invalid(detail)
        }
        return when (version) {
            1 -> ok(v1(node))
            else -> v2(node)
        }
    }

    private fun v1(node: JsonNode): DevicePayload.V1 =
        DevicePayload.V1(
            boot = node.text("boot"),
            seq = node.get("seq").longValue(),
            timestampSeconds = node.get("ts")?.longValue(),
            temperature = node.get("temp")?.doubleValue(),
            humidity = node.get("hum")?.doubleValue(),
        )

    private fun v2(node: JsonNode): Result<DevicePayload, TelemetryRejection> =
        time(node.get("time")?.stringValue()).map { time ->
            DevicePayload.V2(
                boot = node.text("boot"),
                seq = node.get("seq").longValue(),
                time = time,
                readings =
                    node.get("readings").values().map {
                        Reading(name = it.text("name"), value = it.get("value").doubleValue(), unit = it.get("unit")?.stringValue())
                    },
                battery = node.get("battery")?.intValue(),
            )
        }

    /** RFC 3339 の時刻(スキーマの `format` は注釈で検証しないので、ここで読めることを確かめる)。 */
    private fun time(text: String?): Result<Instant?, TelemetryRejection> =
        try {
            ok(text?.let(Instant::parse))
        } catch (_: IllegalArgumentException) {
            invalid("/time", "format")
        }

    public companion object {
        /** 受け付ける形式の版(contracts/devices にあるもの)。版を足すときは、契約・[DevicePayload]・ここを同じ PR で足す。 */
        public val SUPPORTED_VERSIONS: Set<Int> = setOf(1, 2)

        /** 1 件の上限(Broker の max_packet_size と同じ。ADR-0039 §9)。 */
        public const val MAX_PAYLOAD_BYTES: Int = 16 * 1024

        private val mapper = JsonMapper.builder().build()
        private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

        private fun loadSchema(version: Int): Schema {
            val path = "/contracts/devices/telemetry.v$version.schema.json"
            val stream = checkNotNull(DevicePayloadDecoder::class.java.getResourceAsStream(path)) { "$path がクラスパスにありません" }
            return registry.getSchema(stream.use { mapper.readTree(it) })
        }

        private fun invalid(
            location: String,
            keyword: String,
        ): Result<Nothing, TelemetryRejection> = invalid("$location:$keyword")

        private fun invalid(detail: String): Result<Nothing, TelemetryRejection> =
            err(TelemetryRejection(RejectionReason.INVALID_PAYLOAD, detail))

        private fun JsonNode.text(field: String): String = get(field).stringValue()
    }
}
