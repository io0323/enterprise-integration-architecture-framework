package io.eia.integrationflow.domain

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private fun path(text: String): SourcePath = SourcePath.parse(text) ?: error("場所の形が誤っています: $text")

private fun field(
    from: String?,
    transform: Transform = Transform.Copy,
): MappingNode = MappingNode.Field(MappingRule(from?.let(::path), transform))

private fun text(value: String) = SourceValue.Text(value)

private fun obj(vararg fields: Pair<String, SourceValue>) = SourceValue.Obj(fields.toMap())

private fun <T> Result<T, *>.ok(): T = (this as Result.Ok).value

/** contracts/flows/crm-customer.v1.flow.yaml と同じ定義。 */
private val MAPPING =
    linkedMapOf(
        "id" to field("$.id"),
        "name" to field("$.display_name", Transform.Trim),
        "email" to field("$.email", Transform.Lowercase),
        "createdAt" to field("$.created_at", Transform.IsoInstant),
        "updatedAt" to field("$.updated_at", Transform.IsoInstant),
        "billingAddress" to
            MappingNode.Nested(
                path("$.address"),
                linkedMapOf(
                    "countryCode" to field("$.address.country", Transform.Uppercase),
                    "postalCode" to field("$.address.postal_code"),
                    "region" to field("$.address.region"),
                    "city" to field("$.address.city"),
                    "line1" to field("$.address.line1"),
                    "line2" to field("$.address.line2"),
                ),
            ),
    )

private val TRIGGERS =
    listOf(
        Trigger.Event("integration.crm-webhook.received.v1", "crm", setOf("customer.created", "customer.updated"), path("$.data")),
        Trigger.Poll("INT-SALES-006", "customers", 15.minutes),
    )

private fun flow(
    mapping: Map<String, MappingNode> = MAPPING,
    routes: List<Route> = listOf(Route(null, "sales.customer.changed.v1")),
    tables: Map<String, Map<String, String>> = emptyMap(),
    triggers: List<Trigger> = TRIGGERS,
    id: String = "crm-customer",
) = FlowDefinition.of(id, 1, "INT-SALES-007", "sales.Customer", triggers, Ordering("id", path("$.version")), mapping, tables, routes)

private val CUSTOMER =
    obj(
        "id" to text("cus_1"),
        "display_name" to text("  Alice  "),
        "email" to text("Alice@Example.COM"),
        "created_at" to text("2026-10-21T00:00:00Z"),
        "updated_at" to text("2026-10-21T01:00:00Z"),
        "version" to SourceValue.Number("3"),
        "address" to
            obj(
                "country" to text("jp"),
                "postal_code" to text("100-0001"),
                "city" to text("Chiyoda"),
                "line1" to text("1-1"),
                "region" to SourceValue.Null,
            ),
        "loyalty_tier" to text("gold"),
    )

class FlowDefinitionSpec :
    FunSpec({
        test("CRM の顧客を写す: trim・lowercase・uppercase・iso-instant・入れ子。知らない項目は無視し、null は値なし") {
            val mapped = flow().ok().map(CUSTOMER).ok()
            mapped.read("id") shouldBe MappedValue.Text("cus_1")
            mapped.read("name") shouldBe MappedValue.Text("Alice")
            mapped.read("email") shouldBe MappedValue.Text("alice@example.com")
            mapped.read("createdAt") shouldBe MappedValue.Time(Instant.parse("2026-10-21T00:00:00Z"))
            mapped.read("billingAddress.countryCode") shouldBe MappedValue.Text("JP")
            mapped.read("billingAddress.region") shouldBe MappedValue.Absent
            mapped.read("billingAddress.line2") shouldBe MappedValue.Absent
            mapped.read("nothing.here") shouldBe MappedValue.Absent
            mapped.fields.keys shouldBe MAPPING.keys
        }

        test("入れ子の when の場所がなければ、入れ子の型全体を値なしにする") {
            val withoutAddress = SourceValue.Obj(CUSTOMER.fields - "address")
            flow()
                .ok()
                .map(withoutAddress)
                .ok()
                .read("billingAddress") shouldBe MappedValue.Absent
        }

        test("epoch-millis-instant・lookup・constant。数・真偽値の copy は文字列にする") {
            val mapping =
                mapOf(
                    "at" to field("$.ts", Transform.EpochMillisInstant),
                    "country" to field("$.country", Transform.Lookup("countries")),
                    "kind" to field(null, Transform.Constant("customer")),
                    "count" to field("$.count"),
                    "flag" to field("$.flag"),
                )
            val record =
                obj(
                    "ts" to SourceValue.Number("1700000000000"),
                    "country" to text("Japan"),
                    "count" to SourceValue.Number("12"),
                    "flag" to SourceValue.Bool(true),
                )
            val mapped = flow(mapping = mapping, tables = mapOf("countries" to mapOf("Japan" to "JP"))).ok().map(record).ok()
            mapped.read("at") shouldBe MappedValue.Time(Instant.fromEpochMilliseconds(1_700_000_000_000))
            mapped.read("country") shouldBe MappedValue.Text("JP")
            mapped.read("kind") shouldBe MappedValue.Text("customer")
            mapped.read("count") shouldBe MappedValue.Text("12")
            mapped.read("flag") shouldBe MappedValue.Text("true")
        }

        test("値の形が変換に合わなければ、項目の場所だけを示す誤り(値は出さない)") {
            fun error(
                transform: Transform,
                value: SourceValue,
                tables: Map<String, Map<String, String>> = mapOf("t" to mapOf("a" to "b")),
            ): FlowMappingError =
                (flow(mapping = mapOf("x" to field("$.v", transform)), tables = tables).ok().map(obj("v" to value)) as Result.Err).error

            error(Transform.IsoInstant, text("yesterday")).message shouldBe "x: ISO 8601 の日時ではありません"
            error(Transform.EpochMillisInstant, text("soon")).field shouldBe "x"
            error(Transform.Lookup("t"), text("secret@example.com")).message shouldBe "x: 対応表 't' にない値です"
            error(Transform.Trim, obj()).code shouldBe "flow_mapping_failed"
            error(Transform.Copy, SourceValue.Arr(listOf(text("a")))).message shouldBe "x: 文字列・数・真偽値ではありません"
            val nested = mapOf("a" to MappingNode.Nested(null, mapOf("b" to field("$.v", Transform.IsoInstant))))
            (flow(mapping = nested).ok().map(obj("v" to text("bad"))) as Result.Err).error.field shouldBe "a.b"
        }

        test("新旧の判定のキーと版。版は 0 以上の整数(文字列の数も可)") {
            val definition = flow().ok()
            definition.keyOf(definition.map(CUSTOMER).ok()).ok() shouldBe "cus_1"
            definition.versionOf(CUSTOMER).ok() shouldBe 3
            definition.versionOf(obj("version" to text("7"))).ok() shouldBe 7
            listOf(obj(), obj("version" to SourceValue.Number("1.5")), obj("version" to SourceValue.Number("-1"))).forEach {
                (definition.versionOf(it) as Result.Err).error.field shouldBe "$.version"
            }
            (definition.keyOf(MappedRecord(emptyMap())) as Result.Err).error.field shouldBe "id"
        }

        test("振り分け: 上から順に最初に一致した route。どれにも一致しなければ最後の route") {
            val routes =
                listOf(
                    Route("billingAddress.countryCode" to "JP", "sales.customer.changed-jp.v1"),
                    Route("createdAt" to "2026-10-21T00:00:00Z", "sales.customer.created-today.v1"),
                    Route(null, "sales.customer.changed.v1"),
                )
            val definition = flow(routes = routes).ok()
            definition.route(definition.map(CUSTOMER).ok()) shouldBe "sales.customer.changed-jp.v1"
            val us = obj(*(CUSTOMER.fields + ("address" to obj("country" to text("us")))).toList().toTypedArray())
            definition.route(definition.map(us).ok()) shouldBe "sales.customer.created-today.v1"
            definition.route(MappedRecord(emptyMap())) shouldBe "sales.customer.changed.v1"
        }

        test("形では表せない誤りは、Flow の ID と項目ごとに返す") {
            val violations =
                flow(
                    routes = listOf(Route(null, "a.b.c.v1"), Route("id" to "x", "a.b.d.v1")),
                    mapping = mapOf("country" to field("$.c", Transform.Lookup("missing"))),
                    triggers = emptyList(),
                ).shouldBeInstanceOf<Result.Err<ValidationError>>().error.violations
            violations.map { it.field } shouldBe listOf("crm-customer.v1: routes", "crm-customer.v1: mapping", "crm-customer.v1: triggers")
            (flow(mapping = emptyMap()) as Result.Err)
                .error.violations
                .single()
                .field shouldBe "crm-customer.v1: mapping"
        }

        test("Flow の一覧: Webhook の送り手と種類で Flow を選ぶ。同じ ID の Flow は 2 つ置けない") {
            val catalog = FlowCatalog.of(listOf(flow().ok())).ok()
            catalog
                .forEvent("integration.crm-webhook.received.v1", "crm", "customer.updated")
                .single()
                .second.record.text shouldBe "$.data"
            catalog.forEvent("integration.crm-webhook.received.v1", "crm", "customer.deleted") shouldBe emptyList()
            catalog.forEvent("integration.crm-webhook.received.v1", "erp", "customer.updated") shouldBe emptyList()
            (FlowCatalog.of(listOf(flow().ok(), flow().ok())) as Result.Err)
                .error.violations
                .single()
                .reason shouldBe
                "Flow の ID 'crm-customer' が重複しています(1 つの ID に 1 つの版だけを置く)"
            flow().ok().toString() shouldBe "FlowDefinition(crm-customer.v1)"
        }

        test("元のレコードの場所: $ と .項目名 だけ。途中にないものは値なし") {
            path("$").read(CUSTOMER) shouldBe CUSTOMER
            path("$.address.country").read(CUSTOMER) shouldBe text("jp")
            path("$.id.more").read(CUSTOMER) shouldBe null
            listOf("id", "$.a[0]", "$..a", "$.a.", "$.1a").forEach { SourcePath.parse(it) shouldBe null }
            text("secret").toString() shouldBe "Text(***)"
            MappedValue.Text("secret").toString() shouldBe "Text(***)"
        }

        test("新旧の判定: 記録より新しい版だけを出す") {
            VersionDecision.of(null, 1) shouldBe VersionDecision.NEWER
            VersionDecision.of(2, 3) shouldBe VersionDecision.NEWER
            VersionDecision.of(3, 3) shouldBe VersionDecision.SAME
            VersionDecision.of(3, 2) shouldBe VersionDecision.STALE
        }
    })
