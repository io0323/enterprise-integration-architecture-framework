package io.eia.tools.contract.rules

import io.eia.tools.contract.ContractDocument
import io.eia.tools.contract.Contracts
import io.eia.tools.contract.Rule
import io.eia.tools.contract.Violation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions

/** OpenAPI の構造(swagger-parser)・命名・必須装備(Idempotency-Key / 429 / security)の検査。 */
object OpenApiRules {
    private const val IDEMPOTENCY_KEY = "Idempotency-Key"
    private const val RETRY_AFTER = "Retry-After"
    private const val TOO_MANY_REQUESTS = "429"

    /** 検査結果と、他の検査(カタログとの domain 照合)で使う servers の domain。 */
    class Result(
        val violations: List<Violation>,
        val domainsByFile: Map<String, Set<String>>,
    )

    fun check(contracts: Contracts): Result {
        val violations = mutableListOf<Violation>()
        val domains = mutableMapOf<String, Set<String>>()
        contracts.openApis.forEach { document ->
            violations += checkContractFileName(document)
            val parsed = parse(document)
            violations += parsed.messages.map { Violation(document.path, Rule.STRUCT_OPENAPI, it) }
            val api = parsed.api ?: return@forEach
            val (serverDomains, serverViolations) = checkServers(document.path, api)
            domains[document.path] = serverDomains
            violations += serverViolations
            violations += checkOperations(document.path, api)
        }
        return Result(violations, domains)
    }

    private class Parsed(
        val api: OpenAPI?,
        val messages: List<String>,
    )

    private fun parse(document: ContractDocument): Parsed {
        val options =
            ParseOptions().apply {
                isResolve = true
                isResolveFully = true
            }
        val result = OpenAPIV3Parser().readLocation(document.file.toUri().toString(), null, options)
        return Parsed(result.openAPI, result.messages.orEmpty())
    }

    private fun checkServers(
        path: String,
        api: OpenAPI,
    ): Pair<Set<String>, List<Violation>> {
        val servers = api.servers.orEmpty().filterNot { it.url == "/" }
        if (servers.isEmpty()) {
            return emptySet<String>() to listOf(Violation(path, Rule.NAMING_API_SERVER, "servers に Gateway 公開 URL がありません"))
        }
        val results = servers.map { Naming.checkServerUrl(it.url) }
        return results.mapNotNull { it.first }.toSet() to
            results.mapNotNull { it.second }.map { (rule, message) -> Violation(path, rule, message) }
    }

    private fun checkOperations(
        path: String,
        api: OpenAPI,
    ): List<Violation> =
        api.paths.orEmpty().flatMap { (route, item) ->
            val pathViolations = listOfNotNull(Naming.checkApiPath(route)).map { (rule, message) -> Violation(path, rule, message) }
            pathViolations +
                item.readOperationsMap().flatMap { (method, operation) ->
                    val label = "${method.name} $route"
                    val parameters = item.parameters.orEmpty() + operation.parameters.orEmpty()
                    listOfNotNull(
                        checkIdempotencyKey(label, method, parameters),
                        checkRateLimit(label, operation),
                        checkSecurity(label, operation.security ?: api.security),
                    ).map { (rule, message) -> Violation(path, rule, message) }
                }
        }

    private fun checkIdempotencyKey(
        label: String,
        method: PathItem.HttpMethod,
        parameters: List<Parameter>,
    ): Pair<Rule, String>? =
        if (method == PathItem.HttpMethod.POST && parameters.none(::isIdempotencyKey)) {
            Rule.API_IDEMPOTENCY_KEY to "$label に必須の $IDEMPOTENCY_KEY ヘッダがありません"
        } else {
            null
        }

    private fun isIdempotencyKey(parameter: Parameter): Boolean =
        parameter.`in` == "header" && parameter.name.equals(IDEMPOTENCY_KEY, ignoreCase = true) && parameter.required == true

    private fun checkRateLimit(
        label: String,
        operation: Operation,
    ): Pair<Rule, String>? {
        val headers =
            operation.responses
                ?.get(TOO_MANY_REQUESTS)
                ?.headers
                .orEmpty()
                .keys
        return if (headers.none { it.equals(RETRY_AFTER, ignoreCase = true) }) {
            Rule.API_RATE_LIMIT to "$label に 429 + $RETRY_AFTER の応答がありません"
        } else {
            null
        }
    }

    private fun checkSecurity(
        label: String,
        security: List<SecurityRequirement>?,
    ): Pair<Rule, String>? =
        when {
            security.isNullOrEmpty() -> Rule.API_SECURITY to "$label に security が定義されていません"
            security.any { it.isEmpty() } -> Rule.API_SECURITY to "$label の security に空の要件 {} があり、匿名でアクセスできます"
            else -> null
        }
}
