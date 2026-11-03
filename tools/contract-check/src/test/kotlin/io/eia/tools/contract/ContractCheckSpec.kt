package io.eia.tools.contract

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val OPENAPI = "contracts/openapi/order-api.v1.yaml"
private const val ASYNCAPI = "contracts/asyncapi/order-events.v1.yaml"
private const val ORDER_CREATED = "contracts/avro/sales/OrderCreated.avsc"
private const val ORDER_CANCELLED = "contracts/avro/sales/OrderCancelled.avsc"
private const val CATALOG_API = "contracts/catalog/INT-SALES-001.yaml"
private const val CATALOG_EVENTS = "contracts/catalog/INT-SALES-002.yaml"
private const val MANIFEST = "contracts/files/manifest.v1.schema.json"
private const val FILE_SPEC = "contracts/files/sales_daily.v1.yaml"
private const val FILE_SPEC_SCHEMA = "contracts/files/file-spec.v1.schema.json"
private const val CATALOG_FILE = "contracts/catalog/INT-SALES-004.yaml"
private const val COMMAND_CONTRACT = "contracts/asyncapi/inventory-commands.v1.yaml"
private const val COMMAND_CATALOG = "contracts/catalog/INT-INVENTORY-001.yaml"
private const val REASON_FIELD =
    ",\n    { \"name\": \"reason\", \"type\": \"string\", \"doc\": \"取消理由の要約。個人情報を含めない\" }"
private const val WEBHOOK = "contracts/openapi/crm-webhook.v1.yaml"
private const val EXTERNAL_API = "contracts/external/crm-api.v1.yaml"
private const val EXTERNAL_CATALOG = "contracts/catalog/INT-SALES-006.yaml"
private const val FLOW = "contracts/flows/crm-customer.v1.flow.yaml"
private const val EDI_ORDERS = "contracts/files/edi/edifact-orders.v1.yaml"
private const val CATALOG_EDI_ORDERS = "contracts/catalog/INT-B2B-001.yaml"
private const val CATALOG_EDI_APERAK = "contracts/catalog/INT-B2B-003.yaml"
private const val PROTO_STOCK = "contracts/proto/io/eia/inventory/v1/stock_query_service.proto"
private const val PROTO_SHIPMENT = "contracts/proto/io/eia/shipping/v1/shipment_query_service.proto"
private const val CATALOG_PROTO_STOCK = "contracts/catalog/INT-INVENTORY-003.yaml"
private const val CATALOG_PROTO_SHIPMENT = "contracts/catalog/INT-SHIPPING-003.yaml"
private const val SDL = "contracts/graphql/storefront.v1.graphqls"
private const val CATALOG_SDL = "contracts/catalog/INT-STOREFRONT-001.yaml"
private const val REFUND_FIELD = """{ "name": "refundAmount", "type": "long" },"""

/**
 * 違反サンプル 1 件。実際の contracts/ に [prepare] で違反を加え、検出されるルール ID の集合が [expected] と一致することを確かめる。
 * [withBaseline] が true なら、変更前の contracts/ を比較元(main)として互換性検査も行う。
 */
private class Case(
    val name: String,
    val expected: Set<String>,
    val withBaseline: Boolean = false,
    val prepareBaseline: Workspace.() -> Workspace = { this },
    val prepare: Workspace.() -> Workspace,
)

/**
 * 日次の売上ファイル(INT-SALES-004)を active にする(今の状態によらない)。設計中(design)の連携だけが使う契約は破壊的変更を
 * 許すので(ADR-0013 §7)、ファイルの仕様の互換性の違反のサンプルは、比較元と変更後の両方で active にして検査する。
 */
private fun Workspace.fileIntegrationActive(): Workspace = setLifecycle(CATALOG_FILE, "lifecycle: active")

/** 受注の文書(INT-B2B-001)を active にする。EDI の仕様の互換性の違反のサンプルは、比較元と変更後の両方で active にして検査する。 */
private fun Workspace.ediIntegrationActive(): Workspace = setLifecycle(CATALOG_EDI_ORDERS, "lifecycle: active")

/** 受注の文書(INT-B2B-001)を設計中に戻す(今の状態によらない)。設計中の連携だけが使う仕様の変更を許す規則の確認に使う。 */
private fun Workspace.ediIntegrationDesign(): Workspace = setLifecycle(CATALOG_EDI_ORDERS, "lifecycle: design")

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
            replace(COMMAND_CONTRACT, "inventory.stock.cmd-reserve.v1", "inventory.stock.cmdreserve.v1")
                .replace(COMMAND_CATALOG, "inventory.stock.cmd-reserve.v1", "inventory.stock.cmdreserve.v1")
        },
        Case("servers が /{domain} で終わらず、paths が /v{n}/ で始まらない", setOf("CC-NAMING-003", "CC-NAMING-004")) {
            replace(OPENAPI, "url: http://localhost:19080/sales", "url: http://localhost:19080/sales/v1")
                .replace(OPENAPI, "  /v1/orders:", "  /orders:")
        },
        Case("contracts/files のファイル名が規約外", setOf("CC-NAMING-005")) { overlay("file-naming") },
        Case("カタログのファイル名と連携 ID が一致せず、ID が重複", setOf("CC-NAMING-006", "CC-CATALOG-003")) { overlay("duplicate-id") },
        Case("Consumer Group が {service}.{purpose} でない", setOf("CC-NAMING-007")) {
            replace(CATALOG_EVENTS, "group: batch-etl.order-ingest", "group: batch-etl_order-ingest")
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
        Case("Webhook の受け口に x-eia-webhook がない(Idempotency-Key を求める)", setOf("CC-API-001")) {
            replace(WEBHOOK, "      x-eia-webhook: true\n", "")
        },
        Case("Webhook の受け口に署名のヘッダ(webhook-timestamp)がない", setOf("CC-API-004")) {
            replace(WEBHOOK, "        - \$ref: \"#/components/parameters/WebhookTimestamp\"\n", "")
        },
        Case("Webhook の受け口の security が署名の方式ではない", setOf("CC-API-004")) {
            replace(
                WEBHOOK,
                "      type: apiKey\n      in: header\n      name: webhook-signature",
                "      type: apiKey\n      in: query\n      name: signature",
            )
        },
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
            replace(CATALOG_EVENTS, ", group: batch-etl.order-ingest", "")
        },
        Case("カタログ未登録の契約", setOf("CC-CATALOG-006")) { overlay("unregistered-contract") },
        // Flow の connector は external の連携でなければならないので、CC-FLOW-002 も出る
        Case("外部の契約を使う連携に external がない", setOf("CC-CATALOG-007", "CC-FLOW-002")) {
            replace(EXTERNAL_CATALOG, "external: { vendor: Mock CRM(saas-mock), source: services/saas-mock(CRM API v1) }\n", "")
        },
        Case("外部の契約がカタログに登録されていない", setOf("CC-CATALOG-006", "CC-FLOW-002")) {
            delete(EXTERNAL_CATALOG)
        },
        Case("外部の契約のファイル名の版と info.version が違う(構造とファイル名は外部の契約にも当てる)", setOf("CC-NAMING-009")) {
            replace(EXTERNAL_API, "  version: 1.0.0", "  version: 2.0.0")
        },
        // --- Flow(ADR-0036) ---
        Case("Flow にスクリプト(スキーマにない項目)を書く", setOf("CC-FLOW-001")) {
            replace(FLOW, "routes:\n", "script: \"if (x) y\"\nroutes:\n")
        },
        Case("Flow の transform に一覧にない変換を書く", setOf("CC-FLOW-001")) {
            replace(FLOW, "transform: trim", "transform: javascript")
        },
        Case("Flow のファイル名が id と version に合わない", setOf("CC-FLOW-001")) {
            replace(FLOW, "version: 1\n", "version: 2\n")
        },
        Case("Flow の既定の届け先(when のない route)が最後ではない", setOf("CC-FLOW-001")) {
            replace(
                FLOW,
                "  - deliver: sales.customer.changed.v1\n",
                "  - deliver: sales.customer.changed.v1\n  - when: { field: id, equals: x }\n    deliver: sales.customer.changed.v1\n",
            )
        },
        Case("Flow の integration・topic・deliver・connector がカタログにない", setOf("CC-FLOW-002")) {
            replace(FLOW, "integration: INT-SALES-007", "integration: INT-SALES-099")
                .replace(FLOW, "topic: integration.crm-webhook.received.v1", "topic: integration.crm-webhook.unknown.v1")
                .replace(FLOW, "connector: INT-SALES-006", "connector: INT-SALES-002")
        },
        Case("Flow の deliver が integration の channels にない", setOf("CC-FLOW-002")) {
            replace(FLOW, "  - deliver: sales.customer.changed.v1", "  - deliver: sales.order.created.v1")
        },
        Case("Flow の mapping が Canonical にない項目に書き、必須の項目(email)がない", setOf("CC-FLOW-002")) {
            replace(FLOW, "  email: { from: \$.email, transform: lowercase }\n", "  mail: { from: \$.email }\n")
        },
        Case("Flow の入れ子の型(billingAddress)を項目 1 つで書き、ordering の key が Canonical にない", setOf("CC-FLOW-002")) {
            replace(
                FLOW,
                "  billingAddress:\n    when: \$.address\n    fields:\n",
                "  billingAddress: { from: \$.address }\n  ignored:\n    fields:\n",
            ).replace(FLOW, "  key: id\n", "  key: customerId\n")
        },
        Case("Flow の lookup の table が tables にない", setOf("CC-FLOW-002")) {
            replace(
                FLOW,
                "{ from: \$.address.country, transform: uppercase }",
                "{ from: \$.address.country, transform: lookup, table: countries }",
            )
        },
        // --- Command(ADR-0006) ---
        Case("コマンドトピックを 1 つのカタログで 2 サービスが購読", setOf("CC-COMMAND-001")) {
            replace(
                COMMAND_CATALOG,
                "  - { owner: team-inventory, system: inventory-service, group: inventory.command }\n",
                "  - { owner: team-inventory, system: inventory-service, group: inventory.command }\n" +
                    "  - { owner: team-shipping, system: shipping-service, group: shipping.command }\n",
            )
        },
        Case("コマンドトピックを別のカタログからも購読", setOf("CC-COMMAND-001")) {
            overlay("command-second-catalog")
        },
        Case("コマンドトピックの購読者が {service}.command でない", setOf("CC-COMMAND-001")) {
            replace(COMMAND_CATALOG, "group: inventory.command", "group: inventory.reservation")
        },
        Case("コマンドトピックの購読者がいない", setOf("CC-COMMAND-001")) {
            replace(
                COMMAND_CATALOG,
                "consumers:\n  - { owner: team-inventory, system: inventory-service, group: inventory.command }\n",
                "consumers: []\n",
            )
        },
        Case("コマンドトピックを pattern: pub-sub で登録", setOf("CC-COMMAND-002")) {
            replace(COMMAND_CATALOG, "pattern: queue", "pattern: pub-sub")
        },
        // --- File ---
        Case("manifest の必須項目から traceparent を外した", setOf("CC-FILE-001")) {
            replace(MANIFEST, "\"createdAt\", \"traceparent\", \"correlationId\"]", "\"createdAt\", \"correlationId\"]")
        },
        Case("ファイルの仕様: 個人情報の有無がなく、列の型が不明", setOf("CC-FILE-002")) {
            replace(FILE_SPEC, "containsPersonalData: false\n", "")
                .replace(FILE_SPEC, "    type: int64\n", "    type: long\n")
        },
        Case("ファイルの仕様: CSV の区切りの指定がない", setOf("CC-FILE-002")) {
            replace(FILE_SPEC, "    delimiter: \",\"\n", "")
        },
        Case("ファイルの仕様: 列の名前の重複と、key が存在しない列を指す", setOf("CC-FILE-002")) {
            replace(FILE_SPEC, "  - name: ship_region\n", "  - name: ship_country\n")
                .replace(FILE_SPEC, "key: [order_id]", "key: [order_no]")
        },
        Case("ファイルの仕様のスキーマがメタスキーマに違反", setOf("CC-STRUCT-005", "CC-FILE-002")) {
            replace(
                FILE_SPEC_SCHEMA,
                "\"type\": \"object\",\n  \"additionalProperties\"",
                "\"type\": \"objekt\",\n  \"additionalProperties\"",
            )
        },
        Case("ファイルの仕様: 中の dataset と版がファイル名と異なる", setOf("CC-FILE-003")) {
            replace(FILE_SPEC, "dataset: daily", "dataset: monthly").replace(FILE_SPEC, "version: 1", "version: 2")
        },
        Case("ファイルの仕様がカタログに登録されていない", setOf("CC-FILE-004")) { delete(CATALOG_FILE) },
        // --- EDI のサブセットの仕様 ---
        Case("EDI の仕様: 個人情報の有無がない", setOf("CC-EDI-001")) {
            replace(EDI_ORDERS, "containsPersonalData: false\n", "")
        },
        Case("EDI の仕様: inbound なのに業務の規則がない", setOf("CC-EDI-001")) {
            replace(EDI_ORDERS, "businessRules:\n", "errorTexts:\n")
        },
        Case("EDI の仕様: 並びのセグメントが定義されておらず、定義が使われていない", setOf("CC-EDI-002")) {
            replace(EDI_ORDERS, "  - { segment: CNT, status: C, max: 1 }", "  - { segment: MOA, status: C, max: 1 }")
        },
        Case("EDI の仕様: 修飾子が最初のデータ要素の codes にない", setOf("CC-EDI-002")) {
            replace(EDI_ORDERS, "      - { value: DP, status: C, max: 1 }", "      - { value: IV, status: C, max: 1 }")
        },
        Case("EDI の仕様: グループの最初が任意のセグメント", setOf("CC-EDI-002")) {
            replace(EDI_ORDERS, "      - { segment: NAD, status: M, max: 1 }", "      - { segment: NAD, status: C, max: 1 }")
        },
        Case("EDI の仕様: name がファイル名と異なる", setOf("CC-EDI-002")) {
            replace(EDI_ORDERS, "name: edifact-orders\n", "name: edifact-order\n")
        },
        Case("EDI の仕様: UNH のメッセージの型が message と異なる", setOf("CC-EDI-002")) {
            replace(EDI_ORDERS, "message: { type: ORDERS,", "message: { type: ORDCHG,")
        },
        Case("EDI の仕様: コード値が format に収まらず、構文の版が syntax と異なる", setOf("CC-EDI-002")) {
            replace(EDI_ORDERS, "codes: [{ value: \"3\", name: Version 3 }]", "codes: [{ value: \"30\", name: Version 3 }]")
        },
        Case("EDI の仕様: 業務の応答が outbound の仕様でない", setOf("CC-EDI-002")) {
            replace(EDI_ORDERS, "business: contracts/files/edi/edifact-aperak.v1.yaml", "business: $EDI_ORDERS")
        },
        Case("EDI の仕様: 業務の規則のコードが重複", setOf("CC-EDI-002")) {
            replace(EDI_ORDERS, "code: ORD011,", "code: ORD010,")
        },
        Case("EDI の仕様がカタログに登録されていない", setOf("CC-EDI-003")) { delete(CATALOG_EDI_APERAK) },
        Case("style: edi のカタログの contract が EDI の仕様でない", setOf("CC-EDI-003")) {
            replace(CATALOG_EDI_ORDERS, "contract: contracts/files/edi/edifact-orders.v1.yaml", "contract: $FILE_SPEC")
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
        Case("proto: package に版がない", setOf("CC-STRUCT-006")) {
            replace(PROTO_STOCK, "package io.eia.inventory.v1;", "package io.eia.inventory;")
        },
        Case("proto: 構文の誤り", setOf("CC-STRUCT-006")) {
            replace(PROTO_STOCK, "int64 available = 2;", "int64 available = 2")
        },
        Case("proto: 置き場所の形が違う", setOf("CC-NAMING-013")) {
            write("contracts/proto/acme/inventory/v1/stock_query_service.proto", "syntax = \"proto3\";\n\npackage acme.inventory.v1;\n")
                .replace(CATALOG_PROTO_STOCK, PROTO_STOCK, "contracts/proto/acme/inventory/v1/stock_query_service.proto")
                .delete(PROTO_STOCK)
        },
        Case("proto: java_package が決まりと違う", setOf("CC-NAMING-013")) {
            replace(PROTO_STOCK, "io.eia.platform.grpcstubs.inventory.v1", "io.eia.inventory.grpc.v1")
        },
        Case("proto: カタログに登録していない", setOf("CC-CATALOG-006")) {
            write(
                "contracts/proto/io/eia/inventory/v1/unregistered_service.proto",
                "syntax = \"proto3\";\n\npackage io.eia.inventory.v1;\n\noption java_multiple_files = true;\n" +
                    "option java_package = \"io.eia.platform.grpcstubs.inventory.v1\";\n",
            )
        },
        Case("proto: domain が連携 ID と違う", setOf("CC-NAMING-010")) {
            replace(CATALOG_PROTO_STOCK, PROTO_STOCK, PROTO_SHIPMENT)
                .replace(CATALOG_PROTO_SHIPMENT, PROTO_SHIPMENT, PROTO_STOCK)
        },
        Case("GraphQL: 構文の誤り", setOf("CC-STRUCT-007")) {
            replace(SDL, "type OrderBatch {", "type OrderBatch")
        },
        Case("GraphQL: ない型を参照する", setOf("CC-STRUCT-007")) {
            replace(SDL, "  totalAmount: Money!", "  totalAmount: Moneyy!")
        },
        Case("GraphQL: ファイル名の形が違う", setOf("CC-NAMING-014")) {
            rename(SDL, "contracts/graphql/storefront_v1.graphqls")
                .replace(CATALOG_SDL, SDL, "contracts/graphql/storefront_v1.graphqls")
        },
        Case("GraphQL: domain が連携 ID と違う", setOf("CC-NAMING-010")) {
            rename(SDL, "contracts/graphql/sales.v1.graphqls")
                .replace(CATALOG_SDL, SDL, "contracts/graphql/sales.v1.graphqls")
        },
        Case("GraphQL: カタログに登録していない", setOf("CC-CATALOG-006")) {
            write("contracts/graphql/other.v1.graphqls", Workspace.ofRepositoryContracts().read(SDL))
        },
        Case("GraphQL: Mutation を置く(BFF は読み取りだけ)", setOf("CC-GRAPHQL-001")) {
            replace(SDL, "scalar DateTime", "scalar DateTime\n\ntype Mutation {\n  cancel(id: ID!): Boolean\n}")
        },
        Case("GraphQL: Query の項目に @requiresScope がない", setOf("CC-GRAPHQL-002")) {
            replace(SDL, "order(id: ID!): Order @requiresScope(scope: \"sales.order:read\")", "order(id: ID!): Order")
        },
        Case("GraphQL: スコープの形が違う", setOf("CC-GRAPHQL-002")) {
            replace(SDL, "@requiresScope(scope: \"inventory.stock:read\")", "@requiresScope(scope: \"stock\")")
        },
        Case("GraphQL: @queryLimits がない", setOf("CC-GRAPHQL-003")) {
            replace(
                SDL,
                "schema @queryLimits(maxDepth: 8, maxAliases: 10, maxComplexity: 200, maxTokens: 2000, maxBodyBytes: 16384) {",
                "schema {",
            )
        },
        Case("GraphQL: リストの引数に @maxItems がない", setOf("CC-GRAPHQL-003")) {
            replace(SDL, " @maxItems(value: 20, upstreamOperation: \"batchGetOrders\", upstreamParameter: \"ids\")", "")
        },
        Case("GraphQL: @maxItems が下流の REST の上限を超える(承認時の条件 3)", setOf("CC-GRAPHQL-003")) {
            replace(SDL, "@maxItems(value: 20,", "@maxItems(value: 21,")
        },
        Case("GraphQL: REST の上限を下げると、SDL の @maxItems が超える", setOf("CC-GRAPHQL-003")) {
            replace(OPENAPI, "maxItems: 20", "maxItems: 10")
        },
        Case("GraphQL: 下流の操作がない", setOf("CC-GRAPHQL-003")) {
            replace(SDL, "upstreamOperation: \"batchGetOrders\"", "upstreamOperation: \"noSuchOperation\"")
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
        Case("EDI の仕様: グループの回数の変更", setOf("CC-COMPAT-005"), withBaseline = true, prepareBaseline = { ediIntegrationActive() }) {
            ediIntegrationActive().replace(EDI_ORDERS, "    max: 999\n", "    max: 500\n")
        },
        Case("EDI の仕様: 説明の文と名前だけの変更は許す", emptySet(), withBaseline = true, prepareBaseline = { ediIntegrationActive() }) {
            ediIntegrationActive()
                .replace(EDI_ORDERS, "description: GTIN }", "description: 商品の GTIN }")
                .replace(EDI_ORDERS, "name: Buyer }", "name: Buyer party }")
        },
        Case("EDI の仕様: 設計中(design)の連携だけが使う仕様の変更は許す", emptySet(), withBaseline = true, prepareBaseline = { ediIntegrationDesign() }) {
            ediIntegrationDesign().replace(EDI_ORDERS, "    max: 999\n", "    max: 500\n")
        },
        Case("ファイルの仕様: 列の削除と型の変更", setOf("CC-COMPAT-003"), withBaseline = true, prepareBaseline = { fileIntegrationActive() }) {
            fileIntegrationActive()
                .replace(
                    FILE_SPEC,
                    "  - name: ordered_at\n    type: timestamp\n",
                    "  - name: ordered_at\n    type: string\n",
                ).replace(
                    FILE_SPEC,
                    "  - name: ship_region\n    type: string\n    nullable: true\n    description: 届け先の都道府県(ない注文は null)\n",
                    "",
                )
        },
        Case(
            "ファイルの仕様: 列の並べ替えと、必須の列の追加と、enum の値の追加",
            setOf("CC-COMPAT-003"),
            withBaseline = true,
            prepareBaseline = { fileIntegrationActive() },
        ) {
            fileIntegrationActive()
                .replace(
                    FILE_SPEC,
                    "enum: [PLACED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED]",
                    "enum: [PLACED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED, RETURNED]",
                ).replace(FILE_SPEC, "  - name: currency\n", "  - name: currency_code\n")
                .replace(
                    FILE_SPEC,
                    "    description: 届け先の都道府県(ない注文は null)\n",
                    "    description: 届け先の都道府県(ない注文は null)\n" +
                        "  - name: channel\n    type: string\n    nullable: false\n    description: 受付の経路\n",
                )
        },
        Case("ファイルの仕様: 形式の削除と、CSV の改行の変更", setOf("CC-COMPAT-003"), withBaseline = true, prepareBaseline = { fileIntegrationActive() }) {
            fileIntegrationActive()
                .replace(FILE_SPEC, "  - type: parquet\n    extension: parquet\n    compression: zstd\n", "")
                .replace(FILE_SPEC, "    lineSeparator: \"\\r\\n\"", "    lineSeparator: \"\\n\"")
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

        // コマンドトピック(INT-INVENTORY/PAYMENT/SHIPPING-001。ADR-0006・ADR-0029)もリポジトリの契約に含まれる
        test("リポジトリの契約に違反はない(自分自身を比較元にする)") {
            val result = Workspace.ofRepositoryContracts().check(baseline = Workspace.ofRepositoryContracts())

            result.violations.shouldBeEmpty()
            result.baselineUsed shouldBe true
        }

        test("互換な変更(default 付きのフィールド追加・任意項目の追加・ファイルの末尾の任意の列と新しい形式)は違反にならない") {
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
                    ).replace(
                        FILE_SPEC,
                        "    description: 届け先の都道府県(ない注文は null)\n",
                        "    description: 届け先の都道府県(ない注文は null)\n  - name: channel\n    type: string\n    nullable: true\n" +
                            "    description: 受付の経路(末尾に足した任意の列)\n",
                    ).replace(
                        FILE_SPEC,
                        "recordCount:",
                        "  - type: jsonl\n    extension: jsonl.gz\n    compression: gzip\n    encoding: UTF-8\nrecordCount:",
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

        context("proto の破壊的変更(buf。CC-COMPAT-006)") {
            // 状態はリポジトリの値に頼らず明示する(どちらも E2E の後に active になる)
            fun active() =
                Workspace
                    .ofRepositoryContracts()
                    .setLifecycle(CATALOG_PROTO_STOCK, "lifecycle: active")
                    .setLifecycle(CATALOG_PROTO_SHIPMENT, "lifecycle: active")

            test("項目の型の変更") {
                val result = active().replace(PROTO_STOCK, "int64 available = 2;", "string available = 2;").check(active())

                result.ruleIds shouldBe setOf("CC-COMPAT-006")
                result.violations.single().file shouldBe PROTO_STOCK
                result.violations.single().message shouldContain "FIELD_SAME_TYPE"
            }

            test("項目の番号の変更") {
                val result = active().replace(PROTO_STOCK, "string sku = 1;", "string sku = 4;").check(active())

                result.ruleIds shouldBe setOf("CC-COMPAT-006")
            }

            test("RPC の削除") {
                val result =
                    active()
                        .replace(PROTO_STOCK, "  rpc BatchGetStock(BatchGetStockRequest) returns (BatchGetStockResponse);\n", "")
                        .check(active())

                result.ruleIds shouldBe setOf("CC-COMPAT-006")
            }

            test("互換な変更(新しい番号の項目)は通る") {
                val result =
                    active()
                        .replace(PROTO_STOCK, "  int64 available = 2;\n", "  int64 available = 2;\n  string warehouse = 4;\n")
                        .check(active())

                result.violations.shouldBeEmpty()
            }

            test("ファイルの削除は、そのファイルの違反として報告する(buf の報告には path がない)") {
                val result =
                    active()
                        .replace(CATALOG_PROTO_STOCK, PROTO_STOCK, PROTO_SHIPMENT)
                        .delete(PROTO_STOCK)
                        .check(active())

                result.violations.filter { it.rule.id == "CC-COMPAT-006" }.map { it.file } shouldBe listOf(PROTO_STOCK)
            }

            test("proto がすべてなくなっても、黙って通さない") {
                val deleted = Workspace.ofRepositoryContracts()
                listOf(PROTO_STOCK, PROTO_SHIPMENT).forEach { deleted.delete(it) }
                val result = deleted.check(active())

                result.violations
                    .filter { it.rule.id == "CC-COMPAT-006" }
                    .map { it.file }
                    .toSet() shouldBe
                    setOf(PROTO_STOCK, PROTO_SHIPMENT)
            }

            test("設計中の連携だけが使う proto の破壊的変更は許可し、記録する") {
                fun design() = Workspace.ofRepositoryContracts().setLifecycle(CATALOG_PROTO_STOCK, "lifecycle: design")

                val result = design().replace(PROTO_STOCK, "int64 available = 2;", "string available = 2;").check(design())

                result.violations.shouldBeEmpty()
                result.designAllowed.map { it.rule.id }.toSet() shouldBe setOf("CC-COMPAT-006")
            }

            test("比較元に proto がなければ、すべて新規として通る") {
                val baseline = Workspace.ofRepositoryContracts()
                listOf(PROTO_STOCK, PROTO_SHIPMENT).forEach { baseline.delete(it) }

                val result = Workspace.ofRepositoryContracts().check(baseline)

                result.ruleIds.filter { it.startsWith("CC-COMPAT-") }.shouldBeEmpty()
            }
        }

        context("GraphQL の破壊的変更(CC-COMPAT-007)") {
            // 状態はリポジトリの値に頼らず明示する(E2E の後に active になる)
            fun active() = Workspace.ofRepositoryContracts().setLifecycle(CATALOG_SDL, "lifecycle: active")

            listOf(
                "項目の削除" to ("  orderedAt: DateTime!\n" to ""),
                "項目の型の変更" to ("  orderedAt: DateTime!" to "  orderedAt: Long!"),
                "null にならない項目を null にできるようにする" to ("  orderedAt: DateTime!" to "  orderedAt: DateTime"),
                "リストの要素を null にできるようにする" to ("  lines: [OrderLine!]!" to "  lines: [OrderLine]!"),
                "enum の値の削除" to ("  CONFIRMED\n" to ""),
                "必須の引数の追加" to ("order(id: ID!)" to "order(id: ID!, tenant: String!)"),
                "引数の削除" to ("order(id: ID!)" to "order"),
                "引数の型の変更" to ("order(id: ID!)" to "order(id: String!)"),
                "@maxItems を下げる(SchemaDiff には見えない)" to ("@maxItems(value: 20," to "@maxItems(value: 10,"),
                "@queryLimits を下げる(SchemaDiff には見えない)" to ("maxDepth: 8" to "maxDepth: 4"),
                "項目のスコープを変える(SchemaDiff には見えない)" to
                    ("@requiresScope(scope: \"sales.order:read-address\")" to "@requiresScope(scope: \"sales.order:read-pii\")"),
                "項目にスコープを足す(SchemaDiff には見えない)" to
                    ("  customerId: ID!" to "  customerId: ID! @requiresScope(scope: \"sales.order:read-customer\")"),
            ).forEach { (name, change) ->
                test("違反: $name") {
                    val result = active().replace(SDL, change.first, change.second).check(active())

                    withClue(result.violations.joinToString("\n")) { result.ruleIds shouldBe setOf("CC-COMPAT-007") }
                }
            }

            listOf(
                "項目の追加" to ("  orderedAt: DateTime!\n" to "  orderedAt: DateTime!\n  note: String\n"),
                "null にできる項目を null にならないようにする" to ("  line2: String\n" to "  line2: String!\n"),
                "任意の引数の追加" to ("order(id: ID!)" to "order(id: ID!, tenant: String)"),
                "enum の値の追加(一覧にない値は UNKNOWN になる)" to ("  CONFIRMED\n" to "  CONFIRMED\n  RETURNED\n"),
                "上限を上げる" to ("maxDepth: 8" to "maxDepth: 9"),
                "説明の文の変更" to ("\"見つかった注文。指定した順。\"" to "\"見つかった注文(指定した順)。\""),
            ).forEach { (name, change) ->
                test("通す: $name") {
                    active()
                        .replace(SDL, change.first, change.second)
                        .check(active())
                        .violations
                        .shouldBeEmpty()
                }
            }

            test("設計中の連携だけが使う SDL の破壊的変更は許可し、記録する") {
                fun design() = Workspace.ofRepositoryContracts().setLifecycle(CATALOG_SDL, "lifecycle: design")

                val result = design().replace(SDL, "  orderedAt: DateTime!\n", "").check(design())

                result.violations.shouldBeEmpty()
                result.designAllowed.map { it.rule.id }.toSet() shouldBe setOf("CC-COMPAT-007")
            }
        }

        test("buf がなければ、proto があるときに CC-TOOL-001 で失敗する(黙って省略しない)") {
            val result = Workspace.ofRepositoryContracts().check(buf = null)

            result.ruleIds shouldBe setOf("CC-TOOL-001")
            result.violations.single().message shouldContain "buf"
        }

        test("oasdiff がなければ、比較が必要なときに CC-TOOL-001 で失敗する(黙って省略しない)") {
            val result = Workspace.ofRepositoryContracts().check(baseline = Workspace.ofRepositoryContracts(), oasdiff = null)

            result.ruleIds shouldBe setOf("CC-TOOL-001")
        }

        cases.forEach { case ->
            test("違反: ${case.name} → ${case.expected.sorted().joinToString()}") {
                val baseline = if (case.withBaseline) Workspace.ofRepositoryContracts().(case.prepareBaseline)() else null
                val result = Workspace.ofRepositoryContracts().(case.prepare)().check(baseline)

                withClue(result.violations.joinToString("\n")) { result.ruleIds shouldBe case.expected }
            }
        }
    })
