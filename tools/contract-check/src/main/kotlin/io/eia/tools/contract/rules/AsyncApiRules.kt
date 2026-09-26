package io.eia.tools.contract.rules

import io.eia.tools.contract.ContractDocument
import io.eia.tools.contract.Contracts
import io.eia.tools.contract.JsonSchemas
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import io.eia.tools.contract.resolveLocalRef
import io.eia.tools.contract.text
import tools.jackson.databind.JsonNode
import kotlin.io.path.isRegularFile

/** AsyncAPI 3.0 の構造(公式 JSON Schema)・Topic 命名・標準ヘッダ・Avro payload の検査。 */
object AsyncApiRules {
    /** INTEGRATION_STANDARDS §2 の必須ヘッダ(CloudEvents binary mode + トレース)。 */
    val STANDARD_HEADERS = listOf("ce_id", "ce_source", "ce_type", "ce_time", "ce_specversion", "traceparent", "correlationid")
    private const val AVRO_SCHEMA_FORMAT = "application/vnd.apache.avro"
    private const val SUPPORTED_VERSION = "3.0.0"

    /** 検査結果と、カタログとの照合に使う AsyncAPI ファイルごとの channel の address。 */
    class Result(
        val violations: List<Violation>,
        val addressesByFile: Map<String, List<String>>,
    )

    fun check(contracts: Contracts): Result {
        val violations = mutableListOf<Violation>()
        val addresses = mutableMapOf<String, List<String>>()
        contracts.asyncApis.forEach { document ->
            violations += checkStructure(document)
            violations += checkContractFileName(document)
            val channels =
                document.tree
                    .get("channels")
                    ?.properties()
                    ?.map { it.value }
                    .orEmpty()
            val channelAddresses = channels.mapNotNull { it.text("address") }
            addresses[document.path] = channelAddresses
            channelAddresses.forEach { address ->
                Naming.checkTopic(address)?.let { (rule, message) -> violations += Violation(document.path, rule, message) }
            }
            channels.forEach { channel -> violations += checkMessages(contracts, document, channel) }
        }
        return Result(violations, addresses)
    }

    private fun checkStructure(document: ContractDocument): List<Violation> {
        val version = document.tree.text("asyncapi")
        if (version != SUPPORTED_VERSION) {
            return listOf(Violation(document.path, Rule.STRUCT_ASYNCAPI, "asyncapi: '$version' は未対応です($SUPPORTED_VERSION のみ)"))
        }
        return JsonSchemas.validate(JsonSchemas.asyncApi30, document.tree).map { Violation(document.path, Rule.STRUCT_ASYNCAPI, it) }
    }

    private fun checkMessages(
        contracts: Contracts,
        document: ContractDocument,
        channel: JsonNode,
    ): List<Violation> {
        val address = channel.text("address") ?: "(address なし)"
        return channel
            .get("messages")
            ?.properties()
            .orEmpty()
            .flatMap { (name, ref) ->
                val message = ref.resolveLocalRef(document.tree)
                val label = "$address の message '$name'"
                checkHeaders(document, message, label) + checkPayload(contracts, document, message, label)
            }
    }

    private fun checkHeaders(
        document: ContractDocument,
        message: JsonNode,
        label: String,
    ): List<Violation> {
        val headers = message.get("headers")?.resolveLocalRef(document.tree)
        val required =
            headers
                ?.get("required")
                ?.values()
                ?.mapNotNull { if (it.isString) it.stringValue() else null }
                .orEmpty()
                .toSet()
        val missing = STANDARD_HEADERS.filterNot { it in required }
        return if (missing.isEmpty()) {
            emptyList()
        } else {
            listOf(Violation(document.path, Rule.EVENT_HEADERS, "$label のヘッダに必須の ${missing.joinToString()} がありません"))
        }
    }

    private fun checkPayload(
        contracts: Contracts,
        document: ContractDocument,
        message: JsonNode,
        label: String,
    ): List<Violation> {
        val payload = message.get("payload")?.resolveLocalRef(document.tree)
        val format = payload?.text("schemaFormat")
        val ref = payload?.get("schema")?.text("\$ref")
        if (format == null || !format.startsWith(AVRO_SCHEMA_FORMAT) || ref == null) {
            return listOf(Violation(document.path, Rule.EVENT_AVRO_PAYLOAD, "$label の payload が Avro スキーマ(.avsc)を参照していません"))
        }
        val target =
            document.file.parent
                .resolve(ref)
                .normalize()
        val avroDir = contracts.root.resolve(Contracts.CONTRACTS_DIR).resolve("avro")
        return when {
            !target.startsWith(avroDir) || !ref.endsWith(".avsc") -> {
                listOf(Violation(document.path, Rule.EVENT_AVRO_PAYLOAD, "$label の payload '$ref' が contracts/avro の .avsc ではありません"))
            }

            !target.isRegularFile() -> {
                listOf(Violation(document.path, Rule.EVENT_AVRO_PAYLOAD, "$label の payload '$ref' が存在しません"))
            }

            else -> {
                emptyList()
            }
        }
    }
}
