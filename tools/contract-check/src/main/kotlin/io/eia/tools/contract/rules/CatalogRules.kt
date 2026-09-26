package io.eia.tools.contract.rules

import io.eia.tools.contract.CatalogDocument
import io.eia.tools.contract.Contracts
import io.eia.tools.contract.JsonSchemas
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import kotlin.io.path.isRegularFile

/**
 * 連携カタログの検査(Framework 16.1、INTEGRATION_STANDARDS §5)と、コマンドトピックの購読者の検査(ADR-0006)。
 *
 * @param addressesByAsyncApi AsyncAPI ファイルごとの channel の address([AsyncApiRules] の結果)
 * @param domainsByOpenApi OpenAPI ファイルごとの servers の domain([OpenApiRules] の結果)
 */
class CatalogRules(
    private val contracts: Contracts,
    private val addressesByAsyncApi: Map<String, List<String>>,
    private val domainsByOpenApi: Map<String, Set<String>>,
) {
    fun check(): List<Violation> =
        checkSchema() +
            contracts.catalogs.flatMap { checkEntry(it) } +
            checkDuplicateIds() +
            checkUnregisteredContracts() +
            checkCommandTopics()

    private fun checkSchema(): List<Violation> {
        val schemaDocument = contracts.catalogSchema
        val metaErrors = schemaDocument?.let { JsonSchemas.validateAsSchema(it.tree) }.orEmpty()
        return when {
            schemaDocument == null && contracts.catalogs.isNotEmpty() -> {
                listOf(Violation(Contracts.CATALOG_SCHEMA, Rule.CATALOG_SCHEMA, "カタログスキーマがありません"))
            }

            schemaDocument == null -> {
                emptyList()
            }

            metaErrors.isNotEmpty() -> {
                metaErrors.map { Violation(schemaDocument.path, Rule.STRUCT_JSON_SCHEMA, it) }
            }

            else -> {
                val schema = JsonSchemas.schemaOf(schemaDocument.tree)
                contracts.catalogs.flatMap { catalog ->
                    JsonSchemas.validate(schema, catalog.tree).map { Violation(catalog.path, Rule.CATALOG_SCHEMA, it) }
                }
            }
        }
    }

    private fun checkEntry(catalog: CatalogDocument): List<Violation> {
        val entry = catalog.entry
        val violations = mutableListOf<Violation>()
        val domain = entry.id?.let(Naming::integrationDomain)
        if (entry.id != null && catalog.path.substringAfterLast('/') != "${entry.id}.yaml") {
            violations += Violation(catalog.path, Rule.NAMING_INTEGRATION_ID, "ファイル名が連携 ID '${entry.id}' と一致しません")
        }
        entry.contract?.let { violations += checkContract(catalog, it, domain) }
        if (domain != null) {
            violations +=
                entry.channels.filter { Naming.topicDomain(it) != domain }.map {
                    Violation(catalog.path, Rule.NAMING_DOMAIN, "channel '$it' の domain が連携 ID の domain '$domain' と異なります")
                }
        }
        violations += checkConsumers(catalog)
        return violations
    }

    private fun checkContract(
        catalog: CatalogDocument,
        contract: String,
        domain: String?,
    ): List<Violation> {
        val missing =
            if (contracts.root.resolve(contract).isRegularFile()) {
                emptyList()
            } else {
                listOf(Violation(catalog.path, Rule.CATALOG_CONTRACT_EXISTS, "contract '$contract' が存在しません"))
            }
        val channels = addressesByAsyncApi[contract]?.let { checkChannels(catalog, it) }.orEmpty()
        val serverDomains =
            domainsByOpenApi[contract]
                .orEmpty()
                .filter { domain != null && it != domain }
                .map {
                    Violation(
                        catalog.path,
                        Rule.NAMING_DOMAIN,
                        "$contract の servers の domain '$it' が連携 ID の domain '$domain' と異なります",
                    )
                }
        return missing + channels + serverDomains
    }

    private fun checkChannels(
        catalog: CatalogDocument,
        addresses: List<String>,
    ): List<Violation> {
        val registered = catalog.entry.channels.toSet()
        val declared = addresses.toSet()
        return (declared - registered).map {
            Violation(catalog.path, Rule.CATALOG_CHANNELS, "契約の channel '$it' が channels に登録されていません")
        } +
            (registered - declared).map {
                Violation(catalog.path, Rule.CATALOG_CHANNELS, "channels の '$it' が契約 ${catalog.entry.contract} にありません")
            }
    }

    private fun checkConsumers(catalog: CatalogDocument): List<Violation> {
        val requiresGroup = catalog.entry.style in STYLES_WITH_CONSUMER_GROUP
        return catalog.entry.consumers.flatMap { consumer ->
            val group = consumer.group
            when {
                group == null && requiresGroup -> {
                    listOf(
                        Violation(
                            catalog.path,
                            Rule.CATALOG_CONSUMER_GROUP,
                            "consumer '${consumer.system}' に group(Consumer Group)がありません",
                        ),
                    )
                }

                group != null -> {
                    listOfNotNull(Naming.checkConsumerGroup(group)).map { (rule, message) -> Violation(catalog.path, rule, message) }
                }

                else -> {
                    emptyList()
                }
            }
        }
    }

    private fun checkDuplicateIds(): List<Violation> =
        contracts.catalogs
            .filter { it.entry.id != null }
            .groupBy { it.entry.id }
            .filterValues { it.size > 1 }
            .flatMap { (id, documents) ->
                documents.map { document ->
                    Violation(document.path, Rule.CATALOG_DUPLICATE_ID, "連携 ID '$id' が ${documents.joinToString { it.path }} で重複しています")
                }
            }

    private fun checkUnregisteredContracts(): List<Violation> {
        val registered = contracts.catalogs.mapNotNull { it.entry.contract }.toSet()
        return (contracts.openApis + contracts.asyncApis)
            .filter { it.path !in registered }
            .map { Violation(it.path, Rule.CATALOG_UNREGISTERED, "この契約を contract に指定したカタログがありません") }
    }

    /** ADR-0006: コマンドトピックは pattern: queue で、購読は受信サービスの 1 Consumer Group({service}.command)だけ。 */
    private fun checkCommandTopics(): List<Violation> {
        val violations = mutableListOf<Violation>()
        contracts.catalogs.forEach { catalog ->
            val entry = catalog.entry
            val commands = entry.channels.filter(Naming::isCommandTopic)
            if (commands.isNotEmpty() && entry.pattern != QUEUE) {
                violations +=
                    Violation(
                        catalog.path,
                        Rule.COMMAND_PATTERN,
                        "コマンドトピック ${commands.joinToString()} を含むのに pattern が '${entry.pattern}' です",
                    )
            }
            if (entry.pattern == QUEUE) {
                entry.channels.filterNot(Naming::isCommandTopic).forEach {
                    violations += Violation(catalog.path, Rule.COMMAND_PATTERN, "pattern: queue の連携にコマンドトピックでない '$it' があります")
                }
            }
        }
        val catalogsByTopic =
            contracts.catalogs
                .flatMap { catalog ->
                    catalog.entry.channels
                        .filter(Naming::isCommandTopic)
                        .map { it to catalog }
                }.groupBy({ it.first }, { it.second })
        catalogsByTopic.forEach { (topic, catalogs) -> violations += checkSubscribers(topic, catalogs) }
        return violations
    }

    private fun checkSubscribers(
        topic: String,
        catalogs: List<CatalogDocument>,
    ): List<Violation> {
        val subscriptions = catalogs.flatMap { catalog -> catalog.entry.consumers.map { catalog to it } }
        if (subscriptions.size != 1) {
            val subscribers = subscriptions.joinToString { (catalog, consumer) -> "${consumer.system}(${consumer.group})@${catalog.path}" }
            return listOf(
                Violation(
                    catalogs.first().path,
                    Rule.COMMAND_SINGLE_CONSUMER,
                    "コマンドトピック '$topic' の購読者が ${subscriptions.size} 件あります(受信サービスの 1 件のみ)。$subscribers",
                ),
            )
        }
        val (catalog, consumer) = subscriptions.single()
        val receiver = catalog.entry.provider?.system
        val group = consumer.group
        return if (group == null || !group.endsWith(COMMAND_GROUP_SUFFIX) || consumer.system != receiver) {
            listOf(
                Violation(
                    catalog.path,
                    Rule.COMMAND_SINGLE_CONSUMER,
                    "コマンドトピック '$topic' の購読者は受信サービス '$receiver' の {service}.command でなければなりません" +
                        "(実際: ${consumer.system} / $group)",
                ),
            )
        } else {
            emptyList()
        }
    }

    private companion object {
        const val QUEUE = "queue"
        const val COMMAND_GROUP_SUFFIX = ".command"
        val STYLES_WITH_CONSUMER_GROUP = setOf("event", "cdc")
    }
}
