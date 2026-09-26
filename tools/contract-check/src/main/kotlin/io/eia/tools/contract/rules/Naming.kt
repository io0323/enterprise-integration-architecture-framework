package io.eia.tools.contract.rules

import io.eia.tools.contract.Rule

/** INTEGRATION_STANDARDS §1 の命名規約。各関数は違反していれば (ルール, 内容) を返す。 */
object Naming {
    private const val SEGMENT = "[a-z][a-z0-9]*(?:-[a-z0-9]+)*"
    private val TOPIC = Regex("^($SEGMENT)\\.($SEGMENT)\\.($SEGMENT)\\.v[1-9][0-9]*$")
    private val COMMAND_SEGMENT = Regex("^cmd-$SEGMENT$")
    private const val DLQ_SUFFIX = ".dlq"
    private const val COMMAND_PREFIX = "cmd"
    private val CONSUMER_GROUP = Regex("^$SEGMENT\\.$SEGMENT$")
    private val INTEGRATION_ID = Regex("^INT-([A-Z][A-Z0-9]*)-[0-9]{3}$")
    private val DOMAIN = Regex("^$SEGMENT$")
    private val VERSION_SEGMENT = Regex("^v[1-9][0-9]*$")
    private val API_PATH = Regex("^/v[1-9][0-9]*(/($SEGMENT|\\{[a-zA-Z][a-zA-Z0-9]*\\}))+$")
    private val CONTRACT_FILE = Regex("^($SEGMENT)\\.v([1-9][0-9]*)\\.(yaml|yml|json)$")
    private val PASCAL_CASE = Regex("^[A-Z][A-Za-z0-9]*$")
    private val FILE_SPEC = Regex("^$SEGMENT(_$SEGMENT)\\.v[1-9][0-9]*\\.yaml$")
    private val MANIFEST_SCHEMA = Regex("^manifest\\.v[1-9][0-9]*\\.schema\\.json$")
    private val EDI_SPEC = Regex("^edi/$SEGMENT\\.v[1-9][0-9]*\\.yaml$")
    private val FILE_PATTERNS = listOf(FILE_SPEC, MANIFEST_SCHEMA, EDI_SPEC)
    private val FILES_README = setOf("README.md")

    /** Topic 名の検査。DLQ(`{topic}.dlq`)は元の Topic 名で検査する。 */
    fun checkTopic(topic: String): Pair<Rule, String>? {
        val event = TOPIC.matchEntire(topic.removeSuffix(DLQ_SUFFIX))?.groupValues?.get(EVENT_GROUP)
        return when {
            event == null -> {
                Rule.NAMING_TOPIC to "'$topic' は {domain}.{entity}.{event}.v{n}(小文字・kebab-case)ではありません"
            }

            event.startsWith(COMMAND_PREFIX) && !COMMAND_SEGMENT.matches(event) -> {
                Rule.NAMING_COMMAND_TOPIC to "'$topic' の event セグメント '$event' は cmd-{command} の形ではありません"
            }

            else -> {
                null
            }
        }
    }

    fun isCommandTopic(topic: String): Boolean =
        TOPIC
            .matchEntire(topic)
            ?.groupValues
            ?.get(EVENT_GROUP)
            ?.startsWith("cmd-") == true

    /** Topic の先頭セグメント(domain)。 */
    fun topicDomain(topic: String): String = topic.substringBefore('.')

    fun checkConsumerGroup(group: String): Pair<Rule, String>? =
        if (CONSUMER_GROUP.matches(group)) null else Rule.NAMING_CONSUMER_GROUP to "'$group' は {service}.{purpose} ではありません"

    /** 連携 ID の domain(小文字)。形式が違えば null。 */
    fun integrationDomain(id: String): String? =
        INTEGRATION_ID
            .matchEntire(id)
            ?.groupValues
            ?.get(1)
            ?.lowercase()

    /** OpenAPI の servers[*].url の末尾セグメント(domain)を返す。/{domain} で終わっていなければ違反。 */
    fun checkServerUrl(url: String): Pair<String?, Pair<Rule, String>?> {
        val last = url.trimEnd('/').substringAfterLast('/')
        return if (DOMAIN.matches(last) && !VERSION_SEGMENT.matches(last) && url.contains("/")) {
            last to null
        } else {
            null to (Rule.NAMING_API_SERVER to "servers の url '$url' が /{domain} で終わっていません")
        }
    }

    fun checkApiPath(path: String): Pair<Rule, String>? =
        if (API_PATH.matches(path)) {
            null
        } else {
            Rule.NAMING_API_PATH to "path '$path' は /v{n}/{resource}(小文字・kebab-case)ではありません"
        }

    /** OpenAPI / AsyncAPI のファイル名から版(v{n} の n)を返す。形式が違えば null。 */
    fun contractFileVersion(fileName: String): Int? =
        CONTRACT_FILE
            .matchEntire(fileName)
            ?.groupValues
            ?.get(2)
            ?.toInt()

    fun isPascalCase(name: String): Boolean = PASCAL_CASE.matches(name)

    /** contracts/files 配下のファイル名(contracts/files からの相対パス)の検査。 */
    fun checkFileSpec(relativePath: String): Pair<Rule, String>? =
        if (relativePath in FILES_README || FILE_PATTERNS.any { it.matches(relativePath) }) {
            null
        } else {
            Rule.NAMING_FILE to
                "'$relativePath' は {system}_{dataset}.v{n}.yaml / manifest.v{n}.schema.json / edi/{name}.v{n}.yaml のいずれでもありません"
        }

    private const val EVENT_GROUP = 3
}
