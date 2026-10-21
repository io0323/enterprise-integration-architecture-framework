package io.eia.integrationflow.domain

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import kotlin.time.Instant

/** 写した後の値(Canonical の項目の値。Canonical の型への変換は adapters が行う)。 */
public sealed interface MappedValue {
    public data class Text(
        val value: String,
    ) : MappedValue {
        override fun toString(): String = "Text(***)"
    }

    public data class Time(
        val value: Instant,
    ) : MappedValue

    /** 値がない(元のレコードにない・null)。Canonical の必須の項目なら、Canonical への変換で誤りになる。 */
    public data object Absent : MappedValue

    public data class Nested(
        val record: MappedRecord,
    ) : MappedValue
}

/** 写した後のレコード(Canonical の項目の名前 → 値)。 */
public data class MappedRecord(
    val fields: Map<String, MappedValue>,
) {
    /** `a.b` の形の Canonical の項目の値。 */
    public fun read(path: String): MappedValue {
        val head = path.substringBefore('.')
        val value = fields[head] ?: return MappedValue.Absent
        return if ('.' !in path) value else (value as? MappedValue.Nested)?.record?.read(path.substringAfter('.')) ?: MappedValue.Absent
    }

    override fun toString(): String = "MappedRecord(${fields.keys})"
}

/**
 * 写せない(元の値の形が変換に合わない・対応表にない)。業務の再試行では直らないので、取り込みの側で DLQ に隔離する。
 * メッセージには項目の場所だけを入れ、値は入れない(個人情報)。
 */
public data class FlowMappingError(
    val field: String,
    val reason: String,
) : DomainError.NonRetryable {
    override val code: String get() = "flow_mapping_failed"
    override val message: String get() = "${this.field}: $reason"
}

/** [MappingNode] の木に従って、元のレコードを写す。最初の誤りで止める。 */
internal class FlowMapper(
    private val tables: Map<String, Map<String, String>>,
) {
    fun map(
        mapping: Map<String, MappingNode>,
        record: SourceValue,
        prefix: String = "",
    ): Result<MappedRecord, FlowMappingError> {
        val fields = LinkedHashMap<String, MappedValue>()
        for ((name, node) in mapping) {
            when (val value = node(node, record, "$prefix$name")) {
                is Result.Ok -> fields[name] = value.value
                is Result.Err -> return value
            }
        }
        return ok(MappedRecord(fields))
    }

    private fun node(
        node: MappingNode,
        record: SourceValue,
        field: String,
    ): Result<MappedValue, FlowMappingError> =
        when (node) {
            is MappingNode.Field -> {
                apply(node.rule, record, field)
            }

            is MappingNode.Nested -> {
                if (node.whenPresent != null && node.whenPresent.read(record) == null) {
                    ok(MappedValue.Absent)
                } else {
                    map(node.fields, record, "$field.").map { MappedValue.Nested(it) }
                }
            }
        }

    @Suppress("CyclomaticComplexMethod") // 変換の一覧ごとに 1 つの分岐
    private fun apply(
        rule: MappingRule,
        record: SourceValue,
        field: String,
    ): Result<MappedValue, FlowMappingError> {
        val transform = rule.transform
        val source = if (transform is Transform.Constant) null else rule.from?.read(record)
        if (source == null) return ok(if (transform is Transform.Constant) MappedValue.Text(transform.value) else MappedValue.Absent)

        fun fail(reason: String): Result<MappedValue, FlowMappingError> = err(FlowMappingError(field, reason))
        val text = textOf(source)
        return when (transform) {
            Transform.Copy -> {
                text?.let { ok(MappedValue.Text(it)) } ?: fail("文字列・数・真偽値ではありません")
            }

            Transform.Trim -> {
                text?.let { ok(MappedValue.Text(it.trim())) } ?: fail("文字列ではありません")
            }

            Transform.Lowercase -> {
                text?.let { ok(MappedValue.Text(it.lowercase())) } ?: fail("文字列ではありません")
            }

            Transform.Uppercase -> {
                text?.let { ok(MappedValue.Text(it.uppercase())) } ?: fail("文字列ではありません")
            }

            Transform.IsoInstant -> {
                (source as? SourceValue.Text)?.value?.let { Instant.parseOrNull(it) }?.let { ok(MappedValue.Time(it)) }
                    ?: fail("ISO 8601 の日時ではありません")
            }

            Transform.EpochMillisInstant -> {
                text?.toLongOrNull()?.let { ok(MappedValue.Time(Instant.fromEpochMilliseconds(it))) } ?: fail("エポックからのミリ秒(整数)ではありません")
            }

            is Transform.Lookup -> {
                text?.let { tables[transform.table]?.get(it) }?.let { ok(MappedValue.Text(it)) }
                    ?: fail("対応表 '${transform.table}' にない値です")
            }

            is Transform.Constant -> {
                ok(MappedValue.Text(transform.value))
            }
        }
    }

    private fun textOf(value: SourceValue): String? =
        when (value) {
            is SourceValue.Text -> value.value
            is SourceValue.Number -> value.raw
            is SourceValue.Bool -> value.value.toString()
            else -> null
        }
}

/** 記録した最新の版と比べた、新しく受けた版の扱い(ADR-0036 §3)。 */
public enum class VersionDecision {
    /** 記録より新しい(または記録がない)。出して、記録を進める。 */
    NEWER,

    /** 記録と同じ版。出さない(再送・同じ版の同期)。 */
    SAME,

    /** 記録より古い。出さない(順序の逆転・古い版の同期)。 */
    STALE,
    ;

    public companion object {
        public fun of(
            recorded: Long?,
            incoming: Long,
        ): VersionDecision =
            when {
                recorded == null || incoming > recorded -> NEWER
                incoming == recorded -> SAME
                else -> STALE
            }
    }
}
