package io.eia.tools.contract

import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Locale

/** JSON Schema による検証。スキーマ文書の `$schema` で dialect を選び、指定がなければ 2020-12 とする。 */
object JsonSchemas {
    private const val META_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema"
    private const val ASYNCAPI_3_0_SCHEMA = "/asyncapi/asyncapi-3.0.0.schema.json"

    // メッセージの言語を実行環境のロケールに依存させない(出力の他の部分に合わせて日本語に固定する)
    private val registry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
            builder.schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.JAPANESE).build())
        }

    /** AsyncAPI 3.0.0 の公式スキーマ(リソースに同梱。出典は resources/asyncapi/README.md)。 */
    val asyncApi30: Schema by lazy {
        val stream =
            checkNotNull(JsonSchemas::class.java.getResourceAsStream(ASYNCAPI_3_0_SCHEMA)) {
                "$ASYNCAPI_3_0_SCHEMA がクラスパスにありません"
            }
        registry.getSchema(stream.use { JsonMapper.builder().build().readTree(it) })
    }

    private val metaSchema2020: Schema by lazy { registry.getSchema(SchemaLocation.of(META_SCHEMA_2020_12)) }

    fun schemaOf(node: JsonNode): Schema = registry.getSchema(node)

    /** [instance] を [schema] で検証し、違反を「位置: 内容」の文字列で返す。 */
    fun validate(
        schema: Schema,
        instance: JsonNode,
    ): List<String> = schema.validate(instance).map { error -> "${error.instanceLocation}: ${error.message}" }

    /** JSON Schema 2020-12 の文書として正しいか(メタスキーマで検証する)。 */
    fun validateAsSchema(document: JsonNode): List<String> = validate(metaSchema2020, document)
}
