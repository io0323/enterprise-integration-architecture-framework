package io.eia.platform.messagingkafka

import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.trace.TraceParent
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.internals.RecordHeader
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * イベントのメタデータ。Kafka のヘッダに CloudEvents の binary mode(`ce_*`)と `traceparent`・`correlationid` で載せる
 * (INTEGRATION_STANDARDS §2。AsyncAPI の StandardHeaders)。値はすべて UTF-8 の文字列にする。
 * Outbox 経由(ADR-0007)では Debezium が Outbox の列をヘッダに載せるので、同じ名前・同じ形式にそろえる。
 *
 * @property id `ce_id`。イベントごとに一意で、受信側の冪等の判定に使う
 * @property source `ce_source`(例 `/sales/order-service`)
 * @property type `ce_type`([EventTopic.ceType])
 * @property time `ce_time`(RFC 3339。UTC)
 */
public data class EventMetadata(
    public val id: Uuid,
    public val source: String,
    public val type: String,
    public val time: Instant,
    public val traceParent: TraceParent,
    public val correlationId: CorrelationId,
) {
    init {
        require(source.isNotBlank()) { "ce_source が空です" }
        require(type.isNotBlank()) { "ce_type が空です" }
    }

    /** Kafka のヘッダ。 */
    public fun toHeaders(): List<Header> =
        listOf(
            header(CE_ID, id.toString()),
            header(CE_SOURCE, source),
            header(CE_TYPE, type),
            header(CE_TIME, time.toString()),
            header(CE_SPECVERSION, SPEC_VERSION),
            header(TraceParent.HEADER, traceParent.format()),
            header(CORRELATION_ID, correlationId.value),
        )

    public companion object {
        public const val CE_ID: String = "ce_id"
        public const val CE_SOURCE: String = "ce_source"
        public const val CE_TYPE: String = "ce_type"
        public const val CE_TIME: String = "ce_time"
        public const val CE_SPECVERSION: String = "ce_specversion"
        public const val CORRELATION_ID: String = "correlationid"
        public const val SPEC_VERSION: String = "1.0"

        /**
         * Kafka のヘッダから読む(受信側。P07 の Consumer と、テストでの検査に使う)。同じ名前のヘッダが複数あれば最後のものを使う。
         * 必須のヘッダの欠落・形式の不正は、まとめて [ValidationError] にする(値はメッセージに入れない)。
         */
        public fun fromHeaders(headers: Iterable<Header>): Result<EventMetadata, ValidationError> {
            val values = headers.associate { it.key() to it.value()?.toString(Charsets.UTF_8) }
            val violations = mutableListOf<FieldViolation>()

            fun <T> required(
                name: String,
                parse: (String) -> T?,
            ): T? {
                val raw = values[name]
                val parsed = raw?.let { runCatchingParse { parse(it) } }
                if (parsed == null) violations += FieldViolation(name, if (raw == null) "ありません" else "形式が不正です")
                return parsed
            }
            val id = required(CE_ID) { Uuid.parse(it) }
            val source = required(CE_SOURCE) { it.takeIf(String::isNotBlank) }
            val type = required(CE_TYPE) { it.takeIf(String::isNotBlank) }
            val time = required(CE_TIME) { Instant.parse(it) }
            required(CE_SPECVERSION) { it.takeIf { version -> version == SPEC_VERSION } }
            val traceParent = required(TraceParent.HEADER) { TraceParent.parse(it).getOrNull() }
            val correlationId = required(CORRELATION_ID) { CorrelationId.parse(it).getOrNull() }
            return if (violations.isEmpty()) {
                ok(EventMetadata(id!!, source!!, type!!, time!!, traceParent!!, correlationId!!))
            } else {
                err(ValidationError(violations))
            }
        }

        private fun header(
            name: String,
            value: String,
        ): Header = RecordHeader(name, value.toByteArray(Charsets.UTF_8))

        private inline fun <T> runCatchingParse(parse: () -> T?): T? =
            try {
                parse()
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
