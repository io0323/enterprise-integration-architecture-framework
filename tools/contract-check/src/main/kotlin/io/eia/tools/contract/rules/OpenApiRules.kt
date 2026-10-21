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
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions

/**
 * OpenAPI の構造(swagger-parser)・命名・必須装備(Idempotency-Key / 429 / security)の検査。
 * Webhook の受け口(`x-eia-webhook: true`)は、Idempotency-Key の代わりに署名のヘッダと署名の方式を検査する(ADR-0035)。
 * 外部の契約(contracts/external)は、構造とファイル名だけを検査する([checkExternal])。
 */
object OpenApiRules {
    private const val IDEMPOTENCY_KEY = "Idempotency-Key"
    private const val RETRY_AFTER = "Retry-After"
    private const val TOO_MANY_REQUESTS = "429"
    private const val WEBHOOK_EXTENSION = "x-eia-webhook"
    private const val SIGNATURE_HEADER = "webhook-signature"
    private val WEBHOOK_HEADERS = listOf("webhook-id", "webhook-timestamp", SIGNATURE_HEADER)

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

    /**
     * 外部の契約(ベンダーが持ち主の API の写し。ADR-0035 §8)。こちらからは変えられないので、自社の API の規則(命名・Idempotency-Key・
     * 429・security)は当てず、構造(OpenAPI として正しい)とファイル名({name}.v{n}.yaml)だけを検査する。
     */
    fun checkExternal(contracts: Contracts): List<Violation> =
        contracts.externalOpenApis.flatMap { document ->
            checkContractFileName(document) + parse(document).messages.map { Violation(document.path, Rule.STRUCT_OPENAPI, it) }
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
                    val security = operation.security ?: api.security
                    val webhook = operation.extensions?.get(WEBHOOK_EXTENSION) == true
                    listOfNotNull(
                        if (webhook) checkWebhook(label, parameters, security, api) else checkIdempotencyKey(label, method, parameters),
                        checkRateLimit(label, operation),
                        checkSecurity(label, security),
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

    /** Webhook の受け口: 署名の 3 つのヘッダが required で、security に署名の方式(apiKey・header・webhook-signature)がある。 */
    private fun checkWebhook(
        label: String,
        parameters: List<Parameter>,
        security: List<SecurityRequirement>?,
        api: OpenAPI,
    ): Pair<Rule, String>? {
        val headers = parameters.filter { it.`in` == "header" && it.required == true }.map { it.name.lowercase() }.toSet()
        val missing = WEBHOOK_HEADERS.filterNot { it in headers }
        val schemes = api.components?.securitySchemes.orEmpty()
        val signed =
            security.orEmpty().flatMap { it.keys }.any { name ->
                schemes[name]?.let {
                    it.type == SecurityScheme.Type.APIKEY && it.`in` == SecurityScheme.In.HEADER && it.name.equals(SIGNATURE_HEADER, true)
                } == true
            }
        val problems =
            listOfNotNull(
                missing.takeIf { it.isNotEmpty() }?.let { "必須の ${it.joinToString()} ヘッダがありません" },
                "security に署名の方式(apiKey・in: header・name: $SIGNATURE_HEADER)がありません".takeUnless { signed },
            )
        return problems.takeIf { it.isNotEmpty() }?.let { Rule.API_WEBHOOK to "$label(Webhook の受け口)の${it.joinToString("。")}" }
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
