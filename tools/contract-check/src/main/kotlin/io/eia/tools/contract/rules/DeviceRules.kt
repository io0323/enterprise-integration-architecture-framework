package io.eia.tools.contract.rules

import io.eia.tools.contract.ContractDocument
import io.eia.tools.contract.Contracts
import io.eia.tools.contract.JsonSchemas
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import kotlin.io.path.name

/**
 * デバイスの形式のスキーマ(contracts/devices の {message}.v{n}.schema.json。ADR-0039 §4・§5)の検査と、
 * main の版に対する不変(CC-COMPAT-004)。
 */
object DeviceRules {
    /** 重複の判定(ce_id)と形式の判定に要る項目(ADR-0039 §4・§5)。 */
    val REQUIRED = listOf(VERSION_FIELD, "boot", "seq")
    private val README = setOf("${Contracts.DEVICES_DIR}README.md")

    /** 比べるときに除く注釈のキーワード(説明の文は直してよい)。 */
    private val ANNOTATIONS = setOf("description", "title", "examples", "\$comment")

    fun check(contracts: Contracts): List<Violation> =
        checkNaming(contracts) + contracts.devicePayloads.flatMap(::checkSchema) + checkUnreferenced(contracts)

    /** main の版の形式のスキーマを、変えていない・消していない(CC-COMPAT-004)。 */
    fun checkUnchanged(
        current: Contracts,
        baseline: Contracts,
    ): List<Violation> {
        val now = current.devicePayloads.associateBy { it.path }
        return baseline.devicePayloads.mapNotNull { old ->
            val new = now[old.path]
            val problem =
                when {
                    new == null -> "デバイスの形式のスキーマを消しました(この版を送るデバイスが残っているかもしれません)"
                    withoutAnnotations(new.tree) != withoutAnnotations(old.tree) -> "デバイスの形式のスキーマを変えました"
                    else -> null
                }
            problem?.let { Violation(old.path, Rule.COMPAT_DEVICE_PAYLOAD, it) }
        }
    }

    private fun checkNaming(contracts: Contracts): List<Violation> =
        contracts.deviceFiles
            .filterNot { it in README }
            .filter { '/' in it.removePrefix(Contracts.DEVICES_DIR) || Naming.devicePayloadVersion(it.substringAfterLast('/')) == null }
            .map {
                Violation(it, Rule.NAMING_DEVICE_PAYLOAD, "'$it' は contracts/devices/{message}.v{n}.schema.json ではありません")
            }

    private fun checkSchema(document: ContractDocument): List<Violation> {
        val metaErrors = JsonSchemas.validateAsSchema(document.tree)
        if (metaErrors.isNotEmpty()) return metaErrors.map { Violation(document.path, Rule.STRUCT_JSON_SCHEMA, it) }
        val required =
            document.tree
                .get("required")
                ?.values()
                ?.mapNotNull { if (it.isString) it.stringValue() else null }
                .orEmpty()
                .toSet()
        val violations = mutableListOf<Violation>()
        REQUIRED.filterNot { it in required }.takeIf { it.isNotEmpty() }?.let {
            violations += Violation(document.path, Rule.MQTT_PAYLOAD, "required に ${it.joinToString()} がありません")
        }
        val fileVersion = Naming.devicePayloadVersion(document.file.name)
        val constant =
            document.tree
                .get("properties")
                ?.get(VERSION_FIELD)
                ?.get("const")
                ?.takeIf { it.isIntegralNumber }
                ?.asInt()
        if (fileVersion != null && constant != fileVersion) {
            violations +=
                Violation(
                    document.path,
                    Rule.MQTT_PAYLOAD,
                    "properties.$VERSION_FIELD の const が $constant で、ファイル名の版(v$fileVersion)と一致しません",
                )
        }
        return violations
    }

    private fun checkUnreferenced(contracts: Contracts): List<Violation> {
        val referenced =
            contracts.asyncApis
                .filter(MqttRules::isMqtt)
                .flatMap { AsyncApiRules.payloadReferences(contracts, it) }
                .toSet()
        return contracts.devicePayloads
            .filter { it.path !in referenced }
            .map { Violation(it.path, Rule.MQTT_PAYLOAD, "この形式のスキーマを payload に参照する MQTT の契約がありません") }
    }

    /**
     * 注釈のキーワードを除いた写し。[schema] が false の階層(properties などの名前の一覧)のキーは、項目の名前なので除かない。
     */
    private fun withoutAnnotations(
        node: JsonNode,
        schema: Boolean = true,
    ): JsonNode =
        when (node) {
            is ObjectNode -> {
                val copy = node.deepCopy()
                if (schema) copy.remove(ANNOTATIONS)
                copy.properties().toList().forEach { (name, child) ->
                    copy.set(name, withoutAnnotations(child, schema = !schema || name !in NAME_MAPS))
                }
                copy
            }

            is ArrayNode -> {
                val copy = node.deepCopy()
                for (index in 0 until copy.size()) copy.set(index, withoutAnnotations(copy.get(index)))
                copy
            }

            else -> {
                node
            }
        }

    /** 値が「項目の名前 → スキーマ」の対応になるキーワード。 */
    private val NAME_MAPS = setOf("properties", "patternProperties", "\$defs", "dependentSchemas")

    private const val VERSION_FIELD = "v"
}
