package io.eia.bffgraphql.adapters.graphql

import graphql.language.Directive
import graphql.language.FieldDefinition
import graphql.language.IntValue
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.StringValue
import graphql.schema.idl.TypeDefinitionRegistry

/** クエリの上限(SDL の `@queryLimits`)。 */
public data class QueryLimits(
    val maxDepth: Int,
    val maxAliases: Int,
    val maxComplexity: Int,
    val maxTokens: Int,
    val maxBodyBytes: Int,
)

/**
 * 契約の SDL に書いた方針(スコープ・上限)を読む(ADR-0043 §2)。コードに値を複製しない。
 *
 * 契約の検査は contract-check が行うが、ここでも起動時に次を確かめる(宣言の漏れで認可なしのまま動かさない)。
 * - `Query` のすべての項目に `@requiresScope` がある。
 * - `@requiresScope` の項目は null にできる(認可されないときに null を返すため。ADR-0043 §7)。
 * - `@queryLimits` がある。
 */
internal class SdlPolicy private constructor(
    /** 項目(`Type.field`)ごとの必要なスコープ。 */
    val scopes: Map<FieldCoordinate, String>,
    /** 引数(`Type.field` + 引数の名前)ごとの上限。 */
    val maxItems: Map<Pair<FieldCoordinate, String>, Int>,
    val limits: QueryLimits,
) {
    companion object {
        private const val QUERY = "Query"
        private const val REQUIRES_SCOPE = "requiresScope"
        private const val MAX_ITEMS = "maxItems"
        private const val QUERY_LIMITS = "queryLimits"

        fun of(registry: TypeDefinitionRegistry): SdlPolicy {
            val fields =
                registry.getTypes(ObjectTypeDefinition::class.java).flatMap { type ->
                    type.fieldDefinitions.map { FieldCoordinate(type.name, it.name) to it }
                }
            fields.filter { (coordinate, field) -> coordinate.type == QUERY && field.getDirectives(REQUIRES_SCOPE).isEmpty() }.forEach {
                error("SDL の ${it.first} に @requiresScope がありません")
            }
            val scopes = fields.mapNotNull { (coordinate, field) -> scopeOf(field)?.let { coordinate to it } }.toMap()
            fields.filter { (coordinate, field) -> coordinate in scopes && field.type is NonNullType }.forEach {
                error("SDL の ${it.first} は @requiresScope があるので、null にできる型にしてください")
            }
            val maxItems =
                fields
                    .flatMap { (coordinate, field) ->
                        field.inputValueDefinitions.mapNotNull { argument ->
                            argument
                                .getDirectives(MAX_ITEMS)
                                .firstOrNull()
                                ?.int("value")
                                ?.let { (coordinate to argument.name) to it }
                        }
                    }.toMap()
            return SdlPolicy(scopes, maxItems, limits(registry))
        }

        private fun limits(registry: TypeDefinitionRegistry): QueryLimits {
            val directive =
                registry
                    .schemaDefinition()
                    .map { it.getDirectives(QUERY_LIMITS).firstOrNull() }
                    .orElse(null) ?: error("SDL の schema に @queryLimits がありません")
            return QueryLimits(
                maxDepth = directive.required("maxDepth"),
                maxAliases = directive.required("maxAliases"),
                maxComplexity = directive.required("maxComplexity"),
                maxTokens = directive.required("maxTokens"),
                maxBodyBytes = directive.required("maxBodyBytes"),
            )
        }

        private fun scopeOf(field: FieldDefinition): String? =
            (
                field
                    .getDirectives(REQUIRES_SCOPE)
                    .firstOrNull()
                    ?.getArgument("scope")
                    ?.value as? StringValue
            )?.value

        private fun Directive.int(name: String): Int? = (getArgument(name)?.value as? IntValue)?.value?.toInt()

        private fun Directive.required(name: String): Int = int(name) ?: error("@queryLimits に $name がありません")
    }
}

/** スキーマの項目。 */
internal data class FieldCoordinate(
    val type: String,
    val field: String,
) {
    override fun toString(): String = "$type.$field"
}
