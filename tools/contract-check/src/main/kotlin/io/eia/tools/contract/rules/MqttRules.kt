package io.eia.tools.contract.rules

import io.eia.tools.contract.ContractDocument
import io.eia.tools.contract.Contracts
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import io.eia.tools.contract.resolveLocalRef
import io.eia.tools.contract.text
import tools.jackson.databind.JsonNode
import kotlin.io.path.isRegularFile

/** MQTT の契約(servers がすべて MQTT。デバイスと Broker の間。ADR-0039)のメッセージの検査。 */
object MqttRules {
    /** MQTT v5 のユーザープロパティで運ぶヘッダ(INTEGRATION_STANDARDS §2)。v3.1.1 のデバイスは送れないので required にはしない。 */
    val HEADERS = listOf("traceparent", "correlationid")
    private val PROTOCOLS = setOf("mqtt", "secure-mqtt")

    /** servers がすべて MQTT の契約なら true。 */
    fun isMqtt(document: ContractDocument): Boolean {
        val protocols =
            document.tree
                .get("servers")
                ?.properties()
                ?.map { it.value.text("protocol") }
                .orEmpty()
        return protocols.isNotEmpty() && protocols.all { it in PROTOCOLS }
    }

    /** ヘッダ(ユーザープロパティ)に traceparent・correlationid が定義されている(CC-MQTT-001)。 */
    fun checkHeaders(
        document: ContractDocument,
        message: JsonNode,
        label: String,
    ): List<Violation> {
        val defined =
            message
                .get("headers")
                ?.resolveLocalRef(document.tree)
                ?.get("properties")
                ?.propertyNames()
                .orEmpty()
                .toSet()
        val missing = HEADERS.filterNot { it in defined }
        return if (missing.isEmpty()) {
            emptyList()
        } else {
            listOf(Violation(document.path, Rule.MQTT_HEADERS, "$label のヘッダ(ユーザープロパティ)に ${missing.joinToString()} がありません"))
        }
    }

    /** payload が contracts/devices の形式のスキーマを参照する(CC-MQTT-002)。スキーマの中身は [DeviceRules] で検査する。 */
    fun checkPayload(
        contracts: Contracts,
        document: ContractDocument,
        message: JsonNode,
        label: String,
    ): List<Violation> {
        val ref =
            AsyncApiRules.payloadRef(document, message)
                ?: return listOf(violation(document, "$label の payload が contracts/devices の形式のスキーマを参照していません"))
        val target =
            document.file.parent
                .resolve(ref)
                .normalize()
        return when {
            !target.startsWith(contracts.root.resolve(Contracts.DEVICES_DIR)) || !ref.endsWith(".schema.json") -> {
                listOf(violation(document, "$label の payload '$ref' が contracts/devices の形式のスキーマではありません"))
            }

            !target.isRegularFile() -> {
                listOf(violation(document, "$label の payload '$ref' が存在しません"))
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun violation(
        document: ContractDocument,
        message: String,
    ) = Violation(document.path, Rule.MQTT_PAYLOAD, message)
}
