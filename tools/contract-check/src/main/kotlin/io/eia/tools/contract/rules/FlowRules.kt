package io.eia.tools.contract.rules

import com.networknt.schema.Schema
import io.eia.shared.canonical.sales.Customer
import io.eia.tools.contract.ContractDocument
import io.eia.tools.contract.Contracts
import io.eia.tools.contract.JsonSchemas
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import io.eia.tools.contract.text
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.serializer
import tools.jackson.databind.JsonNode

/**
 * iPaaS 風の Flow の定義(contracts/flows の {id}.v{n}.flow.yaml。Framework 10・ADR-0036)の検査。
 *
 * - `CC-FLOW-001`: Flow のスキーマ(flow.v{n}.schema.json)への適合・ファイル名({id}.v{version}.flow.yaml)・
 *   振り分けの形(最後だけが when のない route)。
 * - `CC-FLOW-002`: 参照の存在。integration・connector はカタログの連携(connector は external)、topic・deliver はカタログの channels
 *   (deliver は integration の channels)、lookup の table は tables にあるもの。mapping・ordering.key・route の field は
 *   Canonical Model の項目で、Canonical の必須の項目はすべて mapping にある。
 *
 * integration-flow も起動時に同じスキーマで検証する(誤りがあれば起動しない)。ここでは PR の時点で見つける。
 */
class FlowRules(
    private val contracts: Contracts,
) {
    fun check(): List<Violation> {
        if (contracts.flows.isEmpty()) return emptyList()
        val schemaDocument = contracts.flowSchemas.firstOrNull { it.path == SCHEMA_V1 }
        val metaErrors = schemaDocument?.let { JsonSchemas.validateAsSchema(it.tree) }.orEmpty()
        return when {
            schemaDocument == null -> listOf(Violation(SCHEMA_V1, Rule.FLOW_SCHEMA, "Flow のスキーマがありません"))
            metaErrors.isNotEmpty() -> metaErrors.map { Violation(schemaDocument.path, Rule.STRUCT_JSON_SCHEMA, it) }
            else -> checkFlows(JsonSchemas.schemaOf(schemaDocument.tree))
        }
    }

    private fun checkFlows(schema: Schema): List<Violation> =
        contracts.flows.flatMap { flow ->
            val schemaErrors = JsonSchemas.validate(schema, flow.tree).map { Violation(flow.path, Rule.FLOW_SCHEMA, it) }
            if (schemaErrors.isNotEmpty()) {
                schemaErrors + checkFileName(flow)
            } else {
                checkFileName(flow) + checkRoutesShape(flow) + checkReferences(flow) + checkCanonical(flow)
            }
        }

    private fun checkFileName(flow: ContractDocument): List<Violation> {
        val expected = "${flow.tree.text("id")}.v${flow.tree.get("version")?.asInt()}${Contracts.FLOW_SUFFIX}"
        val actual = flow.path.substringAfterLast('/')
        return if (actual == expected) {
            emptyList()
        } else {
            listOf(Violation(flow.path, Rule.FLOW_SCHEMA, "ファイル名は $expected にします(id と version から)"))
        }
    }

    /** 振り分けは上から順に見る。最後だけが when のない route(どれにも一致しないときの届け先)。 */
    private fun checkRoutesShape(flow: ContractDocument): List<Violation> {
        val routes =
            flow.tree
                .get("routes")
                ?.values()
                .orEmpty()
        val defaults = routes.mapIndexedNotNull { index, route -> index.takeIf { route.get("when") == null } }
        return when {
            defaults != listOf(routes.size - 1) -> {
                listOf(Violation(flow.path, Rule.FLOW_SCHEMA, "routes は、最後の 1 つだけを when のない route(既定の届け先)にします"))
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun checkReferences(flow: ContractDocument): List<Violation> {
        val tree = flow.tree
        val catalogs = contracts.catalogs.mapNotNull { catalog -> catalog.entry.id?.let { it to catalog } }.toMap()
        val allChannels = contracts.catalogs.flatMap { it.entry.channels }.toSet()
        val problems = mutableListOf<String>()
        val integrationId = tree.text("integration")
        val integration = catalogs[integrationId]
        if (integration == null) problems += "integration '$integrationId' がカタログにありません"
        tree.get("triggers")?.values().orEmpty().forEach { trigger ->
            when (trigger.text("kind")) {
                "event" -> {
                    val topic = trigger.text("topic")
                    if (topic !in allChannels) problems += "trigger の topic '$topic' がカタログの channels にありません"
                }

                "poll" -> {
                    val connector = trigger.text("connector")
                    val entry = catalogs[connector]
                    when {
                        entry == null -> problems += "trigger の connector '$connector' がカタログにありません"
                        entry.tree.get("external") == null -> problems += "trigger の connector '$connector' は external の連携ではありません"
                    }
                }
            }
        }
        val channels =
            integration
                ?.entry
                ?.channels
                .orEmpty()
                .toSet()
        tree.get("routes")?.values().orEmpty().mapNotNull { it.text("deliver") }.forEach { topic ->
            if (integration != null && topic !in channels) problems += "deliver '$topic' が $integrationId の channels にありません"
        }
        val tables =
            tree
                .get("tables")
                ?.propertyNames()
                ?.toSet()
                .orEmpty()
        rules(tree.get("mapping")).mapNotNull { it.text("table") }.filterNot { it in tables }.forEach {
            problems += "lookup の table '$it' が tables にありません"
        }
        return problems.map { Violation(flow.path, Rule.FLOW_REFERENCE, it) }
    }

    /** mapping・ordering.key・route の field が Canonical Model の項目で、必須の項目がすべて mapping にある。 */
    private fun checkCanonical(flow: ContractDocument): List<Violation> {
        val canonical = flow.tree.text("canonical")
        val descriptor = CANONICAL[canonical] ?: return emptyList()
        val problems = mutableListOf<String>()
        checkMapping(flow.tree.get("mapping"), descriptor, "", problems)
        val fields =
            listOfNotNull(flow.tree.get("ordering")?.text("key")) +
                flow.tree
                    .get("routes")
                    ?.values()
                    .orEmpty()
                    .mapNotNull { it.get("when")?.text("field") }
        fields.filterNot { leafExists(descriptor, it) }.forEach { problems += "Canonical の $canonical に項目 '$it' がありません" }
        return problems.map { Violation(flow.path, Rule.FLOW_REFERENCE, it) }
    }

    private fun checkMapping(
        mapping: JsonNode?,
        descriptor: SerialDescriptor,
        prefix: String,
        problems: MutableList<String>,
    ) {
        val names = (0 until descriptor.elementsCount).associateBy { descriptor.getElementName(it) }
        val mapped = mapping?.propertyNames()?.toSet().orEmpty()
        mapped.filterNot { it in names }.forEach { problems += "Canonical に項目 '$prefix$it' がありません" }
        names.forEach { (name, index) ->
            val element = descriptor.getElementDescriptor(index)
            val required = !descriptor.isElementOptional(index) && !element.isNullable
            val node = mapping?.get(name)
            when {
                node == null && required -> {
                    problems += "Canonical の必須の項目 '$prefix$name' が mapping にありません"
                }

                node != null && node.has("fields") -> {
                    checkMapping(node.get("fields"), element, "$prefix$name.", problems)
                }

                node != null && element.kind == StructureKind.CLASS && !element.isInline -> {
                    problems += "'$prefix$name' は入れ子の型です。fields で項目ごとに書きます"
                }
            }
        }
    }

    private fun leafExists(
        descriptor: SerialDescriptor,
        path: String,
    ): Boolean {
        val head = path.substringBefore('.')
        val index = (0 until descriptor.elementsCount).firstOrNull { descriptor.getElementName(it) == head } ?: return false
        return if ('.' in path) leafExists(descriptor.getElementDescriptor(index), path.substringAfter('.')) else true
    }

    /** mapping の中の項目の値の取り方(入れ子も含む)。 */
    private fun rules(mapping: JsonNode?): List<JsonNode> =
        mapping?.properties()?.flatMap { (_, node) -> if (node.has("fields")) rules(node.get("fields")) else listOf(node) }.orEmpty()

    private companion object {
        const val SCHEMA_V1 = "contracts/flows/flow.v1.schema.json"

        /** Flow の canonical の名前と、Canonical Model の型(flow.v1.schema.json の enum と揃える)。 */
        val CANONICAL: Map<String, SerialDescriptor> = mapOf("sales.Customer" to serializer<Customer>().descriptor)
    }
}
