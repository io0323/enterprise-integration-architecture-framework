package io.eia.integrationflow.domain

import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Duration

/**
 * Flow の定義(contracts/flows の `{id}.v{version}.flow.yaml`。ADR-0036)。trigger → ordering → map → route → deliver だけを持つ。
 * 任意のスクリプト・式は持たない(Framework 10.4 のアンチパターン)。形の検査(JSON Schema)は adapters が読み込むときに行い、
 * ここでは形では表せない整合([of])を確かめる。
 *
 * @property canonical 変換先の Canonical Model の型の名前(例 `sales.Customer`)。Canonical との変換は adapters が行う(ADR-0010 Decision 7)
 */
@Suppress("LongParameterList") // Flow の定義の項目と 1 対 1
public class FlowDefinition private constructor(
    public val id: String,
    public val version: Int,
    public val integration: String,
    public val canonical: String,
    public val triggers: List<Trigger>,
    public val ordering: Ordering,
    public val mapping: Map<String, MappingNode>,
    public val tables: Map<String, Map<String, String>>,
    public val routes: List<Route>,
) {
    /** 元のレコードを Canonical の項目に写す。 */
    public fun map(record: SourceValue): Result<MappedRecord, FlowMappingError> = FlowMapper(tables).map(mapping, record)

    /** 新旧の判定のキー(写した後の Canonical の項目)。値がなければ誤り。 */
    public fun keyOf(mapped: MappedRecord): Result<String, FlowMappingError> =
        when (val value = mapped.read(ordering.key)) {
            is MappedValue.Text -> ok(value.value)
            else -> err(FlowMappingError(ordering.key, "新旧の判定のキーがありません"))
        }

    /** 元のレコードの版(0 以上の整数)。なければ・整数でなければ誤り。 */
    public fun versionOf(record: SourceValue): Result<Long, FlowMappingError> {
        val raw =
            when (val value = ordering.version.read(record)) {
                is SourceValue.Number -> value.raw
                is SourceValue.Text -> value.value
                else -> null
            }
        val version = raw?.toLongOrNull()?.takeIf { it >= 0 }
        return if (version != null) ok(version) else err(FlowMappingError(ordering.version.text, "版は 0 以上の整数です"))
    }

    /** 届け先。上から順に最初に一致した route、どれにも一致しなければ最後の(条件のない)route。 */
    public fun route(mapped: MappedRecord): String = routes.first { it.matches(mapped) }.deliver

    /** [eventType] の Webhook を、この Flow が取り込むか(送り手と種類で選ぶ)。取り込むなら、その trigger。 */
    public fun eventTrigger(
        topic: String,
        source: String,
        eventType: String,
    ): Trigger.Event? =
        triggers.filterIsInstance<Trigger.Event>().firstOrNull {
            it.topic == topic && it.source == source &&
                eventType in it.eventTypes
        }

    override fun toString(): String = "FlowDefinition($id.v$version)"

    public companion object {
        /** 形では表せない整合: 振り分けは最後だけが条件のない route、lookup の対応表が tables にある、trigger の重複がない。 */
        @Suppress("LongParameterList") // Flow の定義の項目と 1 対 1
        public fun of(
            id: String,
            version: Int,
            integration: String,
            canonical: String,
            triggers: List<Trigger>,
            ordering: Ordering,
            mapping: Map<String, MappingNode>,
            tables: Map<String, Map<String, String>>,
            routes: List<Route>,
        ): Result<FlowDefinition, ValidationError> {
            val violations =
                buildList {
                    if (routes.isEmpty() || routes.dropLast(1).any { it.whenEquals == null } || routes.last().whenEquals != null) {
                        add(FieldViolation("routes", "最後の 1 つだけを条件のない route(既定の届け先)にします"))
                    }
                    rules(mapping).mapNotNull { (it.transform as? Transform.Lookup)?.table }.filterNot { it in tables }.forEach {
                        add(FieldViolation("mapping", "lookup の table '$it' が tables にありません"))
                    }
                    if (triggers.isEmpty()) add(FieldViolation("triggers", "1 つ以上の trigger が要ります"))
                    if (mapping.isEmpty()) add(FieldViolation("mapping", "1 つ以上の項目が要ります"))
                }
            return if (violations.isEmpty()) {
                ok(FlowDefinition(id, version, integration, canonical, triggers, ordering, mapping, tables, routes))
            } else {
                err(ValidationError(violations.map { FieldViolation("$id.v$version: ${it.field}", it.reason) }))
            }
        }

        private fun rules(mapping: Map<String, MappingNode>): List<MappingRule> =
            mapping.values.flatMap { node ->
                when (node) {
                    is MappingNode.Field -> listOf(node.rule)
                    is MappingNode.Nested -> rules(node.fields)
                }
            }
    }
}

/** 取り込みの元(ADR-0036 §2)。 */
public sealed interface Trigger {
    /** 社内の取り込みのトピックの Webhook。[source] と本文の種類([eventTypes])で選び、本文の [record] を元のレコードにする。 */
    public data class Event(
        val topic: String,
        val source: String,
        val eventTypes: Set<String>,
        val record: SourcePath,
    ) : Trigger

    /** SaaS の API の定期の取得。[connector] はカタログの連携 ID。 */
    public data class Poll(
        val connector: String,
        val resource: String,
        val every: Duration,
    ) : Trigger
}

/** 新旧の判定。[key] は Canonical の項目、[version] は元のレコードの版の場所。 */
public data class Ordering(
    val key: String,
    val version: SourcePath,
)

/** Canonical の項目ごとの値の取り方。 */
public sealed interface MappingNode {
    public data class Field(
        val rule: MappingRule,
    ) : MappingNode

    /** 入れ子の型。[whenPresent] が元のレコードになければ、入れ子の型全体を値なしにする。 */
    public data class Nested(
        val whenPresent: SourcePath?,
        val fields: Map<String, MappingNode>,
    ) : MappingNode
}

/** 1 つの項目の値の取り方。[from] は constant のときだけ null。 */
public data class MappingRule(
    val from: SourcePath?,
    val transform: Transform,
)

/** 決まった変換の一覧(ADR-0036 §2。値の形を変えるだけで、業務の判断を含まない)。 */
public sealed interface Transform {
    public data object Copy : Transform

    public data object Trim : Transform

    public data object Lowercase : Transform

    public data object Uppercase : Transform

    public data object IsoInstant : Transform

    public data object EpochMillisInstant : Transform

    public data class Lookup(
        val table: String,
    ) : Transform

    public data class Constant(
        val value: String,
    ) : Transform
}

/** 振り分け。[whenEquals] は Canonical の項目([field])と値の組(等号だけ)。null なら既定の届け先。 */
public data class Route(
    val whenEquals: Pair<String, String>?,
    val deliver: String,
) {
    internal fun matches(mapped: MappedRecord): Boolean {
        val (field, expected) = whenEquals ?: return true
        return when (val value = mapped.read(field)) {
            is MappedValue.Text -> value.value == expected
            is MappedValue.Time -> value.value.toString() == expected
            else -> false
        }
    }
}

/** 起動時に読み込んだ Flow の一覧。同じ ID の Flow を 2 つ持たない。 */
public class FlowCatalog private constructor(
    public val flows: List<FlowDefinition>,
) {
    /** 取り込みのトピックの Webhook を取り込む Flow(なければ、どの Flow も取り込まない種類)。 */
    public fun forEvent(
        topic: String,
        source: String,
        eventType: String,
    ): List<Pair<FlowDefinition, Trigger.Event>> =
        flows.mapNotNull { flow ->
            flow.eventTrigger(topic, source, eventType)?.let { flow to it }
        }

    public companion object {
        public fun of(flows: List<FlowDefinition>): Result<FlowCatalog, ValidationError> {
            val duplicated = flows.groupBy { it.id }.filterValues { it.size > 1 }.keys
            return if (duplicated.isEmpty()) {
                ok(FlowCatalog(flows))
            } else {
                err(ValidationError(duplicated.map { FieldViolation("id", "Flow の ID '$it' が重複しています(1 つの ID に 1 つの版だけを置く)") }))
            }
        }
    }
}
