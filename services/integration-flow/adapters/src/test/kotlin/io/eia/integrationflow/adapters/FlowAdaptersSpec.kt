package io.eia.integrationflow.adapters

import io.eia.integrationflow.adapters.canonical.CanonicalTypes
import io.eia.integrationflow.adapters.flow.FlowSource
import io.eia.integrationflow.adapters.flow.JsonRecords
import io.eia.integrationflow.adapters.flow.YamlFlowDefinitions
import io.eia.integrationflow.application.usecase.LoadFlowsService
import io.eia.integrationflow.application.usecase.TransformRecordService
import io.eia.integrationflow.domain.FlowDefinition
import io.eia.integrationflow.domain.SourceValue
import io.eia.integrationflow.domain.Trigger
import io.eia.shared.canonical.common.Address
import io.eia.shared.canonical.sales.Customer
import io.eia.shared.canonical.sales.CustomerId
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private val CONTRACT_FLOWS = File(checkNotNull(System.getProperty("eia.contractsFlows")))
private val CRM_FLOW = File(CONTRACT_FLOWS, "crm-customer.v1.flow.yaml").readText()
private val ACME_FLOW = checkNotNull(FlowAdaptersSpec::class.java.getResource("/flows/acme-contact.v1.flow.yaml")).readText()

/** saas-mock が返す CRM の顧客(契約の写しにない項目 loyalty_tier・_links も含む)。 */
private const val CRM_CUSTOMER =
    """{"id":"cus_1","display_name":"  Alice Example ","email":"Alice@Example.COM","created_at":"2026-10-21T00:00:00Z",
    "updated_at":"2026-10-21T01:00:00Z","version":3,"address":{"country":"jp","postal_code":"100-0001","city":"Chiyoda","line1":"1-1"},
    "loyalty_tier":"gold","_links":{"self":"/v1/customers/cus_1"}}"""

private fun record(json: String): SourceValue = JsonRecords.parse(json.toByteArray()) ?: fail("JSON ではありません")

/** [files] をディレクトリに書いて読み込む。 */
private fun load(vararg files: Pair<String, String>): Result<List<FlowDefinition>, ValidationError> {
    val dir = Files.createTempDirectory("flows")
    files.forEach { (name, text) -> dir.resolve(name).writeText(text) }
    return YamlFlowDefinitions(FlowSource.Directory(dir)).load().also { dir.toFile().deleteRecursively() }
}

private fun violations(vararg files: Pair<String, String>): List<String> =
    load(*files)
        .shouldBeInstanceOf<Result.Err<ValidationError>>()
        .error.violations
        .map { "${it.field}: ${it.reason}" }

private fun customer(
    flow: FlowDefinition,
    json: String,
): Customer {
    val transformed = TransformRecordService()(flow, record(json)).ok()
    return CanonicalTypes.toCustomer(transformed.mapped).ok()
}

class FlowAdaptersSpec :
    FunSpec({
        test("既定の置き場(イメージに含めた contracts/flows)から読み、起動時の検証を通る。リソースは契約のファイルと同じ") {
            val flows = LoadFlowsService(YamlFlowDefinitions())().ok().flows
            flows.map { it.id } shouldBe listOf("crm-customer")
            checkNotNull(FlowSource::class.java.getResource("/contracts/flows/crm-customer.v1.flow.yaml")).readText() shouldBe CRM_FLOW
            val crm = flows.single()
            crm.triggers shouldBe
                listOf(
                    Trigger.Event(
                        "integration.crm-webhook.received.v1",
                        "crm",
                        setOf("customer.created", "customer.updated"),
                        crm.eventTrigger("integration.crm-webhook.received.v1", "crm", "customer.created")!!.record,
                    ),
                    Trigger.Poll("INT-SALES-006", "customers", 15.minutes),
                )
        }

        test("CRM の顧客(saas-mock の形)を Canonical の Customer にする。知らない項目は無視する") {
            val flow = load("crm-customer.v1.flow.yaml" to CRM_FLOW).ok().single()
            val transformed = TransformRecordService()(flow, record(CRM_CUSTOMER)).ok()
            transformed.key shouldBe "cus_1"
            transformed.version shouldBe 3
            transformed.deliver shouldBe "sales.customer.changed.v1"
            CanonicalTypes.toCustomer(transformed.mapped).ok() shouldBe
                Customer(
                    CustomerId("cus_1"),
                    "Alice Example",
                    "alice@example.com",
                    Instant.parse("2026-10-21T00:00:00Z"),
                    Instant.parse("2026-10-21T01:00:00Z"),
                    Address("JP", "100-0001", "Chiyoda", "1-1"),
                )
            // 住所のない顧客は billingAddress を持たない
            customer(flow, CRM_CUSTOMER.replace(Regex(""""address":\{[^}]*},"""), "")).billingAddress shouldBe null
        }

        test("新しい Flow の YAML を足すだけで、新しいマッピングになる(コードは変えない)") {
            val flows = load("crm-customer.v1.flow.yaml" to CRM_FLOW, "acme-contact.v1.flow.yaml" to ACME_FLOW).ok()
            flows.map { it.id } shouldBe listOf("acme-contact", "crm-customer")
            val acme = flows.first()
            val contact =
                """{"contactId":"acme-42","revision":"9","createdMillis":1700000000000,"modifiedMillis":1700000600000,
                "profile":{"fullName":" Bob ","emailAddress":"BOB@ACME.TEST"},
                "postal":{"countryName":"Japan","zip":"150-0001","town":"Shibuya","street":"2-2"}}"""
            customer(acme, contact) shouldBe
                Customer(
                    CustomerId("acme-42"),
                    "Bob",
                    "bob@acme.test",
                    Instant.fromEpochMilliseconds(1_700_000_000_000),
                    Instant.fromEpochMilliseconds(1_700_000_600_000),
                    Address("JP", "150-0001", "Shibuya", "2-2"),
                )
            // 送り手と種類で Flow を選ぶ
            LoadFlowsService {
                Result.Ok(flows)
            }().ok().forEvent("integration.acme-webhook.received.v1", "acme", "contact.changed").single().first.id shouldBe
                "acme-contact"
        }

        test("写した結果が Canonical の検証を通らなければ誤り(値は出さない)") {
            val flow = load("crm-customer.v1.flow.yaml" to CRM_FLOW).ok().single()
            val transformed = TransformRecordService()(flow, record(CRM_CUSTOMER.replace("Alice@Example.COM", "not-an-email"))).ok()
            val error = CanonicalTypes.toCustomer(transformed.mapped).shouldBeInstanceOf<Result.Err<ValidationError>>().error
            error.violations.single().field shouldBe "email"
            error.message.contains("not-an-email") shouldBe false
            val missing = TransformRecordService()(flow, record(CRM_CUSTOMER.replace(""""email":"Alice@Example.COM",""", ""))).ok()
            CanonicalTypes.toCustomer(missing.mapped).shouldBeInstanceOf<Result.Err<*>>()
        }

        test("起動時の検証: スクリプトや式・知らない変換は書けない(スキーマの違反)。ファイル名は id と version から") {
            violations("crm-customer.v1.flow.yaml" to CRM_FLOW.replace("transform: trim", "transform: script")).joinToString() shouldContain
                "crm-customer.v1.flow.yaml: /mapping/name"
            violations("crm-customer.v1.flow.yaml" to CRM_FLOW + "script: |\n  return record.total * 1.1\n").joinToString() shouldContain
                "script"
            violations("crm-customer.v1.flow.yaml" to CRM_FLOW.replace("from: \$.id", "from: \$..id")).joinToString() shouldContain
                "/mapping/id"
            violations("crm.v1.flow.yaml" to CRM_FLOW) shouldBe
                listOf("crm.v1.flow.yaml: ファイル名は crm-customer.v1.flow.yaml にします(id と version から)")
            violations("crm-customer.v1.flow.yaml" to "id: [unclosed").single() shouldContain "YAML として読めません"
        }

        test("起動時の検証: Canonical にない項目・必須の項目の漏れ・入れ子の書き方・振り分けの形・対応表") {
            val unknownField =
                CRM_FLOW.replace(
                    "  email: { from: \$.email, transform: lowercase }",
                    "  email: { from: \$.email }\n  loyalty: { from: \$.loyalty_tier }",
                )
            violations("crm-customer.v1.flow.yaml" to unknownField) shouldBe
                listOf("crm-customer.v1.flow.yaml: Canonical に項目 'loyalty' がありません")
            val missingEmail = CRM_FLOW.replace("  email: { from: \$.email, transform: lowercase }\n", "")
            violations("crm-customer.v1.flow.yaml" to missingEmail) shouldBe
                listOf("crm-customer.v1.flow.yaml: Canonical の必須の項目 'email' が mapping にありません")
            val flatAddress =
                CRM_FLOW.substringBefore("  billingAddress:") +
                    "  billingAddress: { from: \$.address }\nroutes:\n  - deliver: sales.customer.changed.v1\n"
            violations("crm-customer.v1.flow.yaml" to flatAddress) shouldBe
                listOf("crm-customer.v1.flow.yaml: 'billingAddress' は入れ子の型です。fields で項目ごとに書きます")
            val badRoute =
                CRM_FLOW.replace(
                    "routes:\n  - deliver:",
                    "routes:\n  - when: { field: nickname, equals: x }\n    deliver: sales.customer.changed.v1\n  - deliver:",
                )
            violations("crm-customer.v1.flow.yaml" to badRoute) shouldBe
                listOf("crm-customer.v1.flow.yaml: Canonical の sales.Customer に項目 'nickname' がありません")
            val defaultFirst =
                CRM_FLOW.replace(
                    "routes:\n  - deliver: sales.customer.changed.v1",
                    "routes:\n  - deliver: sales.customer.changed.v1\n" +
                        "  - when: { field: id, equals: x }\n    deliver: sales.customer.changed.v1",
                )
            violations("crm-customer.v1.flow.yaml" to defaultFirst).single() shouldContain "routes: 最後の 1 つだけを条件のない route"
            val noTable = ACME_FLOW.substringBefore("tables:") + ACME_FLOW.substringAfter("    United States: US\n")
            violations("acme-contact.v1.flow.yaml" to noTable).single() shouldContain "lookup の table 'countries' が tables にありません"
        }

        test("起動時の検証: 誤りはすべてのファイルの分をまとめて返す。同じ ID の Flow は 2 つ置けない。置き場が空・読めなければ起動しない") {
            violations(
                "crm.v1.flow.yaml" to CRM_FLOW,
                "acme-contact.v1.flow.yaml" to ACME_FLOW.replace("canonical: sales.Customer", "canonical: sales.Order"),
            ).map { it.substringBefore(":") } shouldContainAll listOf("crm.v1.flow.yaml", "acme-contact.v1.flow.yaml")
            val dir = Files.createTempDirectory("flows")
            dir.resolve("crm-customer.v1.flow.yaml").writeText(CRM_FLOW)
            dir.resolve("notes.txt").writeText("Flow ではないファイルは読まない")
            YamlFlowDefinitions(FlowSource.Directory(dir)).load().ok().size shouldBe 1
            val copy = Files.createDirectory(dir.resolve("copy"))
            copy.resolve("crm-customer.v1.flow.yaml").writeText(CRM_FLOW)
            val twice =
                LoadFlowsService {
                    Result.Ok(
                        YamlFlowDefinitions(
                            FlowSource.Directory(dir),
                        ).load().ok() + YamlFlowDefinitions(FlowSource.Directory(copy)).load().ok(),
                    )
                }()
            twice
                .shouldBeInstanceOf<Result.Err<ValidationError>>()
                .error.violations
                .single()
                .field shouldBe "id"
            dir.toFile().deleteRecursively()

            violations() shouldBe listOf("flows: Flow の定義がありません")
            YamlFlowDefinitions(
                FlowSource.Directory(Path.of("/nonexistent/flows")),
            ).load().shouldBeInstanceOf<Result.Err<ValidationError>>()
        }

        test("元のレコードの JSON: 文字列・数・真偽値・null・配列・オブジェクト。JSON でなければ null") {
            JsonRecords.parse("""{"s":"x","n":1.50,"b":false,"z":null,"a":[1],"o":{}}""".toByteArray()) shouldBe
                SourceValue.Obj(
                    mapOf(
                        "s" to SourceValue.Text("x"),
                        "n" to SourceValue.Number("1.50"),
                        "b" to SourceValue.Bool(false),
                        "z" to SourceValue.Null,
                        "a" to SourceValue.Arr(listOf(SourceValue.Number("1"))),
                        "o" to SourceValue.Obj(emptyMap()),
                    ),
                )
            JsonRecords.parse("not json".toByteArray()) shouldBe null
            JsonRecords.parse(byteArrayOf(0xC3.toByte(), 0x28)) shouldBe null
            JsonRecords.parse("[1]".toByteArray()) shouldBe null
        }
    })
