package io.eia.tools.contract

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

private const val OPENAPI = "contracts/openapi/order-api.v1.yaml"
private const val ASYNCAPI = "contracts/asyncapi/order-events.v1.yaml"
private const val ORDER_CREATED = "contracts/avro/sales/OrderCreated.avsc"
private const val ORDER_CANCELLED = "contracts/avro/sales/OrderCancelled.avsc"
private const val CATALOG_API = "contracts/catalog/INT-SALES-001.yaml"
private const val CATALOG_EVENTS = "contracts/catalog/INT-SALES-002.yaml"
private const val MANIFEST = "contracts/files/manifest.v1.schema.json"
private const val COMMAND_CONTRACT = "contracts/asyncapi/inventory-commands.v1.yaml"
private const val COMMAND_CATALOG = "contracts/catalog/INT-INVENTORY-001.yaml"
private const val REASON_FIELD =
    ",\n    { \"name\": \"reason\", \"type\": \"string\", \"doc\": \"取消理由の要約。個人情報を含めない\" }"
private const val REFUND_FIELD = """{ "name": "refundAmount", "type": "long" },"""

/**
 * 違反サンプル 1 件。実際の contracts/ に [prepare] で違反を加え、検出されるルール ID の集合が [expected] と一致することを確かめる。
 * [withBaseline] が true なら、変更前の contracts/ を比較元(main)として互換性検査も行う。
 */
private class Case(
    val name: String,
    val expected: Set<String>,
    val withBaseline: Boolean = false,
    val prepare: Workspace.() -> Workspace,
)

private val cases =
    listOf(
        // --- 構造 ---
        Case("YAML の構文エラー", setOf("CC-STRUCT-001", "CC-CATALOG-006")) {
            replace(CATALOG_API, "id: INT-SALES-001", "id: [INT-SALES-001")
        },
        Case("OpenAPI の応答に description がない", setOf("CC-STRUCT-002")) {
            replace(OPENAPI, "          description: 登録した\n", "")
        },
        Case("AsyncAPI 3.0 に存在しない action(2.x の publish)", setOf("CC-STRUCT-003")) {
            replace(
                ASYNCAPI,
                "    action: send\n    channel:\n      \$ref: \"#/channels/orderCreated\"",
                "    action: publish\n    channel:\n      \$ref: \"#/channels/orderCreated\"",
            )
        },
        Case("AsyncAPI 2.x の文書", setOf("CC-STRUCT-003")) {
            replace(ASYNCAPI, "asyncapi: 3.0.0", "asyncapi: 2.6.0")
        },
        Case("Avro として読めない", setOf("CC-STRUCT-004")) {
            replace(ORDER_CANCELLED, "\"type\": \"record\"", "\"type\": \"recrod\"")
        },
        Case("manifest スキーマがメタスキーマに違反", setOf("CC-STRUCT-005")) {
            replace(MANIFEST, "\"type\": \"object\"", "\"type\": \"objekt\"")
        },
        // --- 命名 ---
        Case("Topic 名が camelCase", setOf("CC-NAMING-001")) {
            replace(ASYNCAPI, "address: sales.order.created.v1", "address: sales.orderCreated.v1")
                .replace(CATALOG_EVENTS, "  - sales.order.created.v1", "  - sales.orderCreated.v1")
        },
        Case("Command Topic の event セグメントが cmd- でない", setOf("CC-NAMING-002", "CC-COMMAND-002")) {
            overlay("command-base")
                .replace(COMMAND_CONTRACT, "inventory.stock.cmd-reserve.v1", "inventory.stock.cmdreserve.v1")
                .replace(COMMAND_CATALOG, "inventory.stock.cmd-reserve.v1", "inventory.stock.cmdreserve.v1")
        },
        Case("servers が /{domain} で終わらず、paths が /v{n}/ で始まらない", setOf("CC-NAMING-003", "CC-NAMING-004")) {
            replace(OPENAPI, "url: http://localhost:19080/sales", "url: http://localhost:19080/sales/v1")
                .replace(OPENAPI, "  /v1/orders:", "  /orders:")
        },
        Case("contracts/files のファイル名が規約外", setOf("CC-NAMING-005")) { overlay("file-naming") },
        Case("カタログのファイル名と連携 ID が一致せず、ID が重複", setOf("CC-NAMING-006", "CC-CATALOG-003")) { overlay("duplicate-id") },
        Case("Consumer Group が {service}.{purpose} でない", setOf("CC-NAMING-007")) {
            replace(CATALOG_EVENTS, "group: inventory.reservation", "group: inventory_reservation")
        },
        Case("Avro の namespace がディレクトリの domain と異なる", setOf("CC-NAMING-008")) {
            replace(ORDER_CANCELLED, "\"namespace\": \"io.eia.events.sales\"", "\"namespace\": \"io.eia.events.billing\"")
        },
        Case("契約のファイル名に版がない", setOf("CC-NAMING-009")) {
            rename(OPENAPI, "contracts/openapi/order-api.yaml")
                .replace(CATALOG_API, OPENAPI, "contracts/openapi/order-api.yaml")
        },
        Case("info.version のメジャーがファイル名の版と異なる", setOf("CC-NAMING-009")) {
            replace(OPENAPI, "  version: 1.0.0", "  version: 2.0.0")
        },
        Case("servers の domain が連携 ID の domain と異なる", setOf("CC-NAMING-010")) {
            replace(OPENAPI, "url: http://localhost:19080/sales", "url: http://localhost:19080/billing")
        },
        // --- API ---
        Case("POST に Idempotency-Key がない", setOf("CC-API-001")) {
            replace(OPENAPI, "        - \$ref: \"#/components/parameters/IdempotencyKey\"\n", "")
        },
        Case("429 + Retry-After の応答がない", setOf("CC-API-002")) {
            replace(OPENAPI, "        \"429\":\n          \$ref: \"#/components/responses/TooManyRequests\"\n", "")
        },
        Case("security がない・空の要件・空配列(fixture: api-security)", setOf("CC-API-003")) { overlay("api-security") },
        // --- Event ---
        Case("ヘッダに traceparent がない", setOf("CC-EVENT-001")) {
            replace(ASYNCAPI, "ce_specversion, traceparent, correlationid]", "ce_specversion, correlationid]")
        },
        Case("payload の .avsc が存在しない", setOf("CC-EVENT-002")) {
            replace(ASYNCAPI, "../avro/sales/OrderCancelled.avsc", "../avro/sales/Missing.avsc")
        },
        // --- Catalog ---
        Case("Owner の欠落", setOf("CC-CATALOG-001")) {
            replace(CATALOG_EVENTS, "provider: { owner: team-order, system: order-service }", "provider: { system: order-service }")
        },
        Case("Owner が個人名", setOf("CC-CATALOG-001")) {
            replace(CATALOG_API, "provider: { owner: team-order,", "provider: { owner: tanaka,")
        },
        Case("Tier の欠落", setOf("CC-CATALOG-001")) { replace(CATALOG_API, "tier: 1\n", "") },
        Case("SLO と機密区分の値が不正", setOf("CC-CATALOG-001")) {
            replace(CATALOG_API, "dataClassification: confidential", "dataClassification: secret")
                .replace(CATALOG_API, "latencyP99: \"500ms\"", "latencyP99: \"fast\"")
        },
        Case("contract が存在しない", setOf("CC-CATALOG-002", "CC-CATALOG-006")) {
            replace(CATALOG_API, OPENAPI, "contracts/openapi/missing.v1.yaml")
        },
        Case("channels に契約の channel が足りない", setOf("CC-CATALOG-004")) {
            replace(CATALOG_EVENTS, "  - sales.order.cancelled.v1\n", "")
        },
        Case("event の consumer に group がない", setOf("CC-CATALOG-005")) {
            replace(CATALOG_EVENTS, ", group: inventory.reservation", "")
        },
        Case("カタログ未登録の契約", setOf("CC-CATALOG-006")) { overlay("unregistered-contract") },
        // --- Command(ADR-0006) ---
        Case("コマンドトピックを 1 つのカタログで 2 サービスが購読", setOf("CC-COMMAND-001")) {
            overlay("command-base").replace(
                COMMAND_CATALOG,
                "  - { owner: team-inventory, system: inventory-service, group: inventory.command }\n",
                "  - { owner: team-inventory, system: inventory-service, group: inventory.command }\n" +
                    "  - { owner: team-shipping, system: shipping-service, group: shipping.command }\n",
            )
        },
        Case("コマンドトピックを別のカタログからも購読", setOf("CC-COMMAND-001")) {
            overlay("command-base").overlay("command-second-catalog")
        },
        Case("コマンドトピックの購読者が {service}.command でない", setOf("CC-COMMAND-001")) {
            overlay("command-base").replace(COMMAND_CATALOG, "group: inventory.command", "group: inventory.reservation")
        },
        Case("コマンドトピックの購読者がいない", setOf("CC-COMMAND-001")) {
            overlay("command-base").replace(
                COMMAND_CATALOG,
                "consumers:\n  - { owner: team-inventory, system: inventory-service, group: inventory.command }\n",
                "consumers: []\n",
            )
        },
        Case("コマンドトピックを pattern: pub-sub で登録", setOf("CC-COMMAND-002")) {
            overlay("command-base").replace(COMMAND_CATALOG, "pattern: queue", "pattern: pub-sub")
        },
        // --- File ---
        Case("manifest の必須項目から traceparent を外した", setOf("CC-FILE-001")) {
            replace(MANIFEST, "\"createdAt\", \"traceparent\", \"correlationId\"]", "\"createdAt\", \"correlationId\"]")
        },
        // --- 互換性(main との差分) ---
        Case("Avro: default のない必須フィールドの追加", setOf("CC-COMPAT-001"), withBaseline = true) {
            replace(
                ORDER_CANCELLED,
                "{ \"name\": \"orderId\", \"type\": \"string\" },",
                "{ \"name\": \"orderId\", \"type\": \"string\" },\n    $REFUND_FIELD",
            )
        },
        Case("Avro: フィールドの型変更(string → long)", setOf("CC-COMPAT-001"), withBaseline = true) {
            replace(ORDER_CANCELLED, "{ \"name\": \"orderId\", \"type\": \"string\" }", "{ \"name\": \"orderId\", \"type\": \"long\" }")
        },
        Case("Avro: enum の値の削除", setOf("CC-COMPAT-001", "CC-CANON-001"), withBaseline = true) {
            replace(ORDER_CREATED, "\"DELIVERED\", \"CANCELLED\"", "\"DELIVERED\"")
        },
        Case("Avro: default のない項目の削除(FORWARD だけが壊れる)", setOf("CC-COMPAT-001"), withBaseline = true) {
            replace(ORDER_CANCELLED, REASON_FIELD, "")
        },
        Case("Avro: enum 値の追加(FORWARD だけが壊れる)", setOf("CC-COMPAT-001", "CC-CANON-001"), withBaseline = true) {
            replace(ORDER_CREATED, "\"DELIVERED\", \"CANCELLED\"", "\"DELIVERED\", \"CANCELLED\", \"RETURNED\"")
        },
        Case("OpenAPI: 必須パラメータの追加", setOf("CC-COMPAT-002"), withBaseline = true) {
            replace(
                OPENAPI,
                "        - name: orderId\n          in: path\n",
                "        - name: tenant\n          in: query\n          required: true\n          schema:\n            type: string\n" +
                    "        - name: orderId\n          in: path\n",
            )
        },
        Case("OpenAPI: パスの削除", setOf("CC-COMPAT-002"), withBaseline = true) {
            replace(OPENAPI, "  /v1/orders/{orderId}:\n", "  /v1/orders/{orderId}/deprecated-path:\n")
        },
        Case("OpenAPI: 応答の必須項目の削除", setOf("CC-COMPAT-002"), withBaseline = true) {
            replace(
                OPENAPI,
                "required: [id, customerId, status, orderedAt, lines, totalAmount, shippingAddress]",
                "required: [id, customerId, status, orderedAt, lines, shippingAddress]",
            )
        },
        // --- Canonical ⇔ Avro(ADR-0012) ---
        Case("Canonical と Avro: 型の不一致(Int ⇔ long)", setOf("CC-CANON-001")) {
            replace(ORDER_CREATED, "{ \"name\": \"lineNumber\", \"type\": \"int\" }", "{ \"name\": \"lineNumber\", \"type\": \"long\" }")
        },
        Case("Canonical と Avro: 項目の欠落", setOf("CC-CANON-001")) {
            replace(ORDER_CREATED, "                  { \"name\": \"sku\", \"type\": \"string\" },\n", "")
        },
        Case("Canonical と Avro: 必須性の不一致", setOf("CC-CANON-001")) {
            replace(
                ORDER_CREATED,
                "{ \"name\": \"region\", \"type\": [\"null\", \"string\"], \"default\": null }",
                "{ \"name\": \"region\", \"type\": \"string\" }",
            )
        },
        Case("Canonical と Avro: Money の項目名が minorUnits でない", setOf("CC-CANON-001")) {
            replace(ORDER_CREATED, "{ \"name\": \"minorUnits\", \"type\": \"long\" }", "{ \"name\": \"amountMinor\", \"type\": \"long\" }")
        },
        Case("Canonical と Avro: Instant が timestamp-millis", setOf("CC-CANON-001")) {
            replace(ORDER_CREATED, "\"logicalType\": \"timestamp-micros\"", "\"logicalType\": \"timestamp-millis\"")
        },
        Case("Canonical と Avro: 対応づけた型がない", setOf("CC-CANON-002")) {
            replace(ORDER_CREATED, "\"name\": \"Order\",", "\"name\": \"OrderSnapshot\",")
        },
    )

class ContractCheckSpec :
    FunSpec({
        test("リポジトリの契約に違反はない(比較元なし)") {
            val result = Workspace.ofRepositoryContracts().check()

            result.violations.shouldBeEmpty()
            result.baselineUsed shouldBe false
        }

        test("リポジトリの契約に違反はない(自分自身を比較元にする)") {
            val result = Workspace.ofRepositoryContracts().check(baseline = Workspace.ofRepositoryContracts())

            result.violations.shouldBeEmpty()
            result.baselineUsed shouldBe true
        }

        test("準拠したコマンドトピック(fixture: command-base)に違反はない") {
            Workspace
                .ofRepositoryContracts()
                .overlay("command-base")
                .check()
                .violations
                .shouldBeEmpty()
        }

        test("互換な変更(default 付きのフィールド追加・任意項目の追加)は違反にならない") {
            val workspace =
                Workspace
                    .ofRepositoryContracts()
                    .replace(
                        ORDER_CANCELLED,
                        "{ \"name\": \"orderId\", \"type\": \"string\" },",
                        "{ \"name\": \"orderId\", \"type\": \"string\" },\n" +
                            "    { \"name\": \"refundAmount\", \"type\": \"long\", \"default\": 0 },\n" +
                            "    { \"name\": \"note\", \"type\": [\"null\", \"string\"], \"default\": null },",
                    ).replace(
                        OPENAPI,
                        "        correlationId:\n          type: string\n        errors:",
                        "        correlationId:\n          type: string\n        retryable:\n          type: boolean\n        errors:",
                    )

            workspace.check(baseline = Workspace.ofRepositoryContracts()).violations.shouldBeEmpty()
        }

        test("FULL 互換: 必須項目の追加は BACKWARD、default のない項目の削除は FORWARD として報告する") {
            val baseline = Workspace.ofRepositoryContracts()
            val added =
                Workspace
                    .ofRepositoryContracts()
                    .replace(
                        ORDER_CANCELLED,
                        "{ \"name\": \"orderId\", \"type\": \"string\" },",
                        "{ \"name\": \"orderId\", \"type\": \"string\" },\n    $REFUND_FIELD",
                    ).check(baseline)
            val removed = Workspace.ofRepositoryContracts().replace(ORDER_CANCELLED, REASON_FIELD, "").check(baseline)

            added.violations.map { it.message.substringBefore(':') }.toSet() shouldBe setOf("BACKWARD")
            removed.violations.map { it.message.substringBefore(':') }.toSet() shouldBe setOf("FORWARD")
        }

        test("FULL 互換: default 付きの項目は削除してもよい") {
            val baseline =
                Workspace
                    .ofRepositoryContracts()
                    .replace(
                        ORDER_CANCELLED,
                        REASON_FIELD,
                        "$REASON_FIELD,\n    { \"name\": \"note\", \"type\": [\"null\", \"string\"], \"default\": null }",
                    )

            Workspace
                .ofRepositoryContracts()
                .check(baseline)
                .violations
                .shouldBeEmpty()
        }

        test("oasdiff がなければ、比較が必要なときに CC-TOOL-001 で失敗する(黙って省略しない)") {
            val result = Workspace.ofRepositoryContracts().check(baseline = Workspace.ofRepositoryContracts(), oasdiff = null)

            result.ruleIds shouldBe setOf("CC-TOOL-001")
        }

        cases.forEach { case ->
            test("違反: ${case.name} → ${case.expected.sorted().joinToString()}") {
                val baseline = if (case.withBaseline) Workspace.ofRepositoryContracts() else null
                val result = Workspace.ofRepositoryContracts().(case.prepare)().check(baseline)

                withClue(result.violations.joinToString("\n")) { result.ruleIds shouldBe case.expected }
            }
        }
    })
