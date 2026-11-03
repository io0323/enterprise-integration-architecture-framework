package io.eia.tools.contract.rules

import graphql.GraphQLException
import graphql.language.Directive
import graphql.language.DirectivesContainer
import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.IntValue
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.StringValue
import graphql.language.Type
import graphql.schema.diff.SchemaDiff
import graphql.schema.diff.SchemaDiffSet
import graphql.schema.diff.reporting.CapturingReporter
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.eia.tools.contract.Contracts
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import io.eia.tools.contract.text
import kotlin.io.path.readText

/**
 * GraphQL の契約(contracts/graphql)の検査(ADR-0043 §2・§3)。
 *
 * - `CC-STRUCT-007`: SDL として読めて、スキーマとして正しい。
 * - `CC-NAMING-014`: ファイル名。
 * - `CC-GRAPHQL-001`: 読み取りだけ(`Mutation`・`Subscription` を置かない)。BFF は業務のロジックを持たない。
 * - `CC-GRAPHQL-002`: `Query` のすべての項目に `@requiresScope`(宣言の漏れで認可なしにしない)。
 * - `CC-GRAPHQL-003`: `@queryLimits` があり、リストの引数に `@maxItems` があり、下流の API の上限以下である。
 * - `CC-COMPAT-007`: main の版からの破壊的変更。構造は graphql-java の `SchemaDiff`、適用したディレクティブ(`SchemaDiff` には見えない)はここで比べる。
 */
@Suppress("TooManyFunctions") // 規則ごと・ディレクティブごとの小さな関数
object GraphqlRules {
    private const val REQUIRES_SCOPE = "requiresScope"
    private const val MAX_ITEMS = "maxItems"
    private const val QUERY_LIMITS = "queryLimits"
    private const val SCOPE_ARGUMENT = "scope"
    private const val VALUE = "value"
    private val LIMIT_ARGUMENTS = listOf("maxDepth", "maxAliases", "maxComplexity", "maxTokens", "maxBodyBytes")
    private val SCOPE = Regex("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+:[a-z][a-z0-9-]*$")

    fun check(contracts: Contracts): List<Violation> =
        contracts.graphqlFiles.flatMap { path ->
            val naming =
                if (Naming.graphqlFileDomain(path) == null) {
                    listOf(Violation(path, Rule.NAMING_GRAPHQL, "ファイル名が contracts/graphql/{domain}.v{n}.graphqls ではありません"))
                } else {
                    emptyList()
                }
            naming +
                when (val parsed = parse(contracts.root.resolve(path).readText())) {
                    is Parsed.Invalid -> listOf(Violation(path, Rule.STRUCT_GRAPHQL, parsed.message))
                    is Parsed.Valid -> checkSchema(contracts, path, parsed.registry)
                }
        }

    /** 比較元に同じパスの SDL があるものを比べる。どちらかが壊れていれば比べない(`CC-STRUCT-007` で報告する)。 */
    fun checkCompatibility(
        current: Contracts,
        baseline: Contracts,
    ): List<Violation> =
        current.graphqlFiles.filter { it in baseline.graphqlFiles }.flatMap { path ->
            val before = baseline.root.resolve(path).readText()
            val after = current.root.resolve(path).readText()
            val beforeParsed = parse(before) as? Parsed.Valid
            val afterParsed = parse(after) as? Parsed.Valid
            if (beforeParsed == null || afterParsed == null) {
                emptyList()
            } else {
                structural(before, after).map { Violation(path, Rule.COMPAT_GRAPHQL, it) } +
                    directives(beforeParsed.registry, afterParsed.registry).map { Violation(path, Rule.COMPAT_GRAPHQL, it) }
            }
        }

    private sealed interface Parsed {
        class Valid(
            val registry: TypeDefinitionRegistry,
        ) : Parsed

        class Invalid(
            val message: String,
        ) : Parsed
    }

    private fun parse(sdl: String): Parsed =
        try {
            val registry = SchemaParser().parse(sdl)
            UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
            Parsed.Valid(registry)
        } catch (e: GraphQLException) {
            Parsed.Invalid(e.message.orEmpty().take(MAX_MESSAGE))
        }

    private fun checkSchema(
        contracts: Contracts,
        path: String,
        registry: TypeDefinitionRegistry,
    ): List<Violation> = readOnly(path, registry) + scopes(path, registry) + limits(contracts, path, registry)

    private fun readOnly(
        path: String,
        registry: TypeDefinitionRegistry,
    ): List<Violation> =
        listOf("Mutation", "Subscription").filter { registry.getTypeOrNull(it) != null }.map {
            Violation(path, Rule.GRAPHQL_READ_ONLY, "$it を置いています")
        }

    private fun scopes(
        path: String,
        registry: TypeDefinitionRegistry,
    ): List<Violation> {
        val missing =
            registry.objectType("Query")?.fieldDefinitions.orEmpty().filter { it.getDirectives(REQUIRES_SCOPE).isEmpty() }.map {
                Violation(path, Rule.GRAPHQL_SCOPES, "Query.${it.name} に @requiresScope がありません")
            }
        val malformed =
            registry.objectTypes().flatMap { type ->
                type.fieldDefinitions.mapNotNull { field ->
                    scopeOf(field)?.takeUnless(SCOPE::matches)?.let {
                        Violation(path, Rule.GRAPHQL_SCOPES, "${type.name}.${field.name} のスコープ '$it' が {domain}.{entity}:{action} ではありません")
                    }
                }
            }
        return missing + malformed
    }

    private fun limits(
        contracts: Contracts,
        path: String,
        registry: TypeDefinitionRegistry,
    ): List<Violation> {
        val limits = registry.schemaDefinition().map { it.getDirectives(QUERY_LIMITS).firstOrNull() }.orElse(null)
        val schema =
            if (limits == null) {
                listOf(Violation(path, Rule.GRAPHQL_LIMITS, "schema に @queryLimits がありません"))
            } else {
                emptyList()
            }
        val arguments =
            registry.objectTypes().flatMap { type ->
                type.fieldDefinitions.flatMap { field ->
                    field.inputValueDefinitions.filter { isList(it.type) }.flatMap { argument ->
                        checkListArgument(contracts, path, "${type.name}.${field.name}(${argument.name})", argument)
                    }
                }
            }
        return schema + arguments
    }

    @Suppress("ReturnCount") // 検査の段ごとに返す
    private fun checkListArgument(
        contracts: Contracts,
        path: String,
        name: String,
        argument: InputValueDefinition,
    ): List<Violation> {
        val directive =
            argument.getDirectives(MAX_ITEMS).firstOrNull()
                ?: return listOf(Violation(path, Rule.GRAPHQL_LIMITS, "$name に @maxItems がありません"))
        val value = directive.intArgument(VALUE) ?: return emptyList()
        val operation = directive.stringArgument("upstreamOperation") ?: return emptyList()
        val parameter = directive.stringArgument("upstreamParameter").orEmpty()
        val upstream =
            upstreamMaxItems(contracts, operation, parameter)
                ?: return listOf(Violation(path, Rule.GRAPHQL_LIMITS, "$name の下流 $operation の $parameter に maxItems がありません"))
        return if (value > upstream) {
            listOf(Violation(path, Rule.GRAPHQL_LIMITS, "$name の @maxItems($value)が下流 $operation の上限($upstream)を超えています"))
        } else {
            emptyList()
        }
    }

    /** OpenAPI の [operation] の引数 [parameter] の `maxItems`。 */
    private fun upstreamMaxItems(
        contracts: Contracts,
        operation: String,
        parameter: String,
    ): Long? =
        contracts.openApis
            .asSequence()
            .flatMap { document ->
                document.tree
                    .get("paths")
                    ?.values()
                    .orEmpty()
            }.flatMap { path -> path.values() }
            .firstOrNull { it.text("operationId") == operation }
            ?.get("parameters")
            ?.values()
            ?.firstOrNull { it.text("name") == parameter }
            ?.get("schema")
            ?.get("maxItems")
            ?.takeIf { it.isNumber }
            ?.longValue()

    private fun structural(
        before: String,
        after: String,
    ): List<String> {
        val reporter = CapturingReporter()
        SchemaDiff().diffSchema(SchemaDiffSet.diffSetFromSdl(before, after), reporter)
        return reporter.breakages.map { "${it.reasonMsg} [${it.category}]" }
    }

    /** 上限を下げる・スコープを変える(足す)のは、今の呼び出しを拒否することになるので破壊的変更。 */
    private fun directives(
        before: TypeDefinitionRegistry,
        after: TypeDefinitionRegistry,
    ): List<String> {
        val scopesAfter = fieldsOf(after).mapValues { (_, field) -> scopeOf(field) }
        val scopes =
            fieldsOf(before).mapNotNull { (name, field) ->
                val old = scopeOf(field)
                if (name in scopesAfter && scopesAfter[name] != old) "$name の @requiresScope を変えました($old → ${scopesAfter[name]})" else null
            }
        val itemsAfter = maxItemsOf(after)
        val items =
            maxItemsOf(before).mapNotNull { (name, old) ->
                itemsAfter[name]?.takeIf { it < old }?.let { "$name の @maxItems を下げました($old → $it)" }
            }
        return scopes + items + limitsLowered(before, after)
    }

    @Suppress("ReturnCount") // どちらかに @queryLimits がなければ比べない(`CC-GRAPHQL-003` で報告する)
    private fun limitsLowered(
        before: TypeDefinitionRegistry,
        after: TypeDefinitionRegistry,
    ): List<String> {
        val old = before.queryLimits() ?: return emptyList()
        val new = after.queryLimits() ?: return emptyList()
        return LIMIT_ARGUMENTS.mapNotNull { name ->
            val oldValue = old.intArgument(name) ?: return@mapNotNull null
            val newValue = new.intArgument(name) ?: return@mapNotNull null
            if (newValue < oldValue) "@queryLimits の $name を下げました($oldValue → $newValue)" else null
        }
    }

    private fun TypeDefinitionRegistry.queryLimits(): Directive? =
        schemaDefinition().map { it.getDirectives(QUERY_LIMITS).firstOrNull() }.orElse(null)

    private fun fieldsOf(registry: TypeDefinitionRegistry): Map<String, FieldDefinition> =
        registry
            .objectTypes()
            .flatMap { type -> type.fieldDefinitions.map { "${type.name}.${it.name}" to it } }
            .toMap()

    private fun maxItemsOf(registry: TypeDefinitionRegistry): Map<String, Long> =
        fieldsOf(registry)
            .flatMap { (name, field) ->
                field.inputValueDefinitions.mapNotNull { argument ->
                    argument
                        .getDirectives(MAX_ITEMS)
                        .firstOrNull()
                        ?.intArgument(VALUE)
                        ?.let { "$name(${argument.name})" to it }
                }
            }.toMap()

    private fun scopeOf(field: DirectivesContainer<*>): String? =
        field.getDirectives(REQUIRES_SCOPE).firstOrNull()?.stringArgument(SCOPE_ARGUMENT)

    private fun Directive.intArgument(name: String): Long? = (getArgument(name)?.value as? IntValue)?.value?.toLong()

    private fun Directive.stringArgument(name: String): String? = (getArgument(name)?.value as? StringValue)?.value

    private fun isList(type: Type<*>): Boolean =
        when (type) {
            is ListType -> true
            is NonNullType -> isList(type.type)
            else -> false
        }

    private fun TypeDefinitionRegistry.objectTypes(): List<ObjectTypeDefinition> = getTypes(ObjectTypeDefinition::class.java)

    private fun TypeDefinitionRegistry.objectType(name: String): ObjectTypeDefinition? = getTypeOrNull(name) as? ObjectTypeDefinition

    private const val MAX_MESSAGE = 500
}
