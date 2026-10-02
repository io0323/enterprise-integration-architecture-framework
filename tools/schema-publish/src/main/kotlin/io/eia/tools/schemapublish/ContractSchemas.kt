package io.eia.tools.schemapublish

import io.eia.platform.schemaregistry.SchemaSubject
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * `contracts/asyncapi` の各チャネル(`address` = トピック名)と、そのメッセージの Avro スキーマ(`payload.schema.$ref`)の組を集める。
 *
 * - `$ref` は文書内の参照(`#/components/...`)と、AsyncAPI のファイルからの相対パス(`../avro/sales/OrderCreated.avsc`)を辿る。
 * - 1 つのチャネルには 1 つのスキーマだけを許す(レジストリの `{topic}-value` のアーティファクトは、トピックごとに 1 つのため)。
 * - 形式の検査は contract-check の役割なので、ここでは登録に必要な項目がなければ例外にするだけにする。
 */
internal object ContractSchemas {
    private val yaml = YAMLMapper.builder().build()

    fun load(contractsRoot: Path): List<SchemaSubject> {
        val asyncApiDir = contractsRoot.resolve("asyncapi")
        val files =
            Files.list(asyncApiDir).use { stream ->
                stream.filter { it.isRegularFile() && it.extension in setOf("yaml", "yml") }.sorted().toList()
            }
        val subjects = files.flatMap(::subjectsOf)
        val duplicated = subjects.groupBy { it.topic }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "複数の AsyncAPI に同じトピックがあります: $duplicated" }
        return subjects
    }

    private fun subjectsOf(file: Path): List<SchemaSubject> {
        val document = yaml.readTree(file.readText())
        val channels = document.path("channels")
        return channels.propertyNames().map { name ->
            val channel = resolve(document, channels.path(name))
            val topic = channel.path("address").stringValue(null) ?: error("$file の channels.$name に address がありません")
            val schemas =
                channel
                    .path("messages")
                    .let { messages -> messages.propertyNames().map { resolve(document, messages.path(it)) } }
                    .map { message -> schemaFile(file, document, message) }
                    .distinct()
            require(schemas.size == 1) { "$file の $topic のスキーマは 1 つにしてください(${schemas.size} 個)" }
            SchemaSubject(topic, schemas.single().readText())
        }
    }

    private fun schemaFile(
        file: Path,
        document: JsonNode,
        message: JsonNode,
    ): Path {
        val payload = resolve(document, message.path("payload"))
        val ref = payload.path("schema").path("\$ref").stringValue(null) ?: error("$file の payload.schema に \$ref がありません")
        require(!ref.startsWith("#")) { "$file: Avro のスキーマは contracts/avro のファイルを参照してください($ref)" }
        val schema = file.parent.resolve(ref).normalize()
        require(schema.isRegularFile()) { "$file が参照する $schema がありません" }
        return schema
    }

    /** 文書内の `$ref`(`#/a/b`)を辿る。参照でなければそのまま返す。 */
    private fun resolve(
        document: JsonNode,
        node: JsonNode,
    ): JsonNode {
        val ref = node.path("\$ref").stringValue(null) ?: return node
        require(ref.startsWith("#/")) { "文書の外への \$ref は payload.schema だけで使えます: $ref" }
        val target = document.at(ref.removePrefix("#"))
        require(!target.isMissingNode) { "\$ref の参照先がありません: $ref" }
        return resolve(document, target)
    }
}
