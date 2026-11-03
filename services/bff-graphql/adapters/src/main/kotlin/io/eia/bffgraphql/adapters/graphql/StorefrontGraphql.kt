package io.eia.bffgraphql.adapters.graphql

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.GraphqlErrorBuilder
import graphql.introspection.Introspection
import graphql.parser.ParserOptions
import io.eia.bffgraphql.application.port.inbound.LoadOrdersUseCase
import io.eia.bffgraphql.application.port.inbound.LoadShipmentsUseCase
import io.eia.bffgraphql.application.port.inbound.LoadStockLevelsUseCase
import io.eia.platform.security.jwt.VerifiedToken
import kotlinx.coroutines.future.await
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory

/**
 * 契約の SDL を実行する(INT-STOREFRONT-001。ADR-0043)。業務のロジックは持たず、下流の呼び出し・結合・形の変換・項目の選択だけを行う。
 *
 * @param introspectionEnabled 既定は無効(本番)。ローカルでだけ有効にする
 */
@Suppress("LongParameterList") // 下流のユースケースと設定。app の配線で 1 か所だけ呼ぶ
public class StorefrontGraphql(
    sdl: String,
    orders: LoadOrdersUseCase,
    stock: LoadStockLevelsUseCase,
    shipments: LoadShipmentsUseCase,
    private val metrics: GraphqlMetrics,
    rateLimiter: ComplexityRateLimiter,
    budget: UpstreamBudget = UpstreamBudget(),
    private val introspectionEnabled: Boolean = false,
) {
    private val storefront = StorefrontSchema.of(sdl, metrics)
    private val upstreams = Upstreams(orders, stock, shipments, budget)

    /** クエリの上限(SDL の `@queryLimits`)。 */
    public val limits: QueryLimits get() = storefront.policy.limits

    init {
        require(rateLimiter.capacity >= limits.maxComplexity) { "バケットの大きさは、1 回のクエリの複雑度の上限(${limits.maxComplexity})以上にしてください" }
    }

    private val parserOptions =
        ParserOptions
            .getDefaultOperationParserOptions()
            .transform { it.maxTokens(limits.maxTokens) }

    private val graphql =
        GraphQL
            .newGraphQL(storefront.schema)
            .instrumentation(QueryLimitInstrumentation(storefront.policy, rateLimiter, metrics::recordRejected))
            .defaultDataFetcherExceptionHandler(DataFetcherErrors)
            .build()

    /** 実行する。トークンのスコープで項目を認可する。 */
    public suspend fun execute(
        query: String,
        operationName: String?,
        variables: Map<String, Any?>,
        token: VerifiedToken,
    ): ExecutionResult =
        // 下流の失敗は項目のエラーにする。1 つの失敗でほかの読み取りを取り消さない(部分応答。ADR-0043 §6)
        supervisorScope {
            val request = RequestContext(token)
            val input =
                ExecutionInput
                    .newExecutionInput()
                    .query(query)
                    .operationName(operationName)
                    .variables(variables)
                    .dataLoaderRegistry(upstreams.registry(this))
                    .graphQLContext(
                        mapOf(
                            RequestContext.KEY to request,
                            ParserOptions::class.java to parserOptions,
                            Introspection.INTROSPECTION_DISABLED to !introspectionEnabled,
                        ),
                    ).build()
            graphql.executeAsync(input).await().also { result ->
                record(result)
                if (request.readSensitive) logger.info("個人情報の項目を読みました(client={})", token.clientId)
            }
        }

    /** 読めなかったサービスごとに、部分応答を 1 回数える(1 つの応答で項目の数だけ数えない)。 */
    private fun record(result: ExecutionResult) {
        if (!result.isDataPresent) {
            // 構文・検証の誤りと上限の超過(上限は onRejected で数えている)
            return
        }
        result.errors
            .mapNotNull { it.extensions?.get("service") as? String }
            .distinct()
            .forEach(metrics::recordPartial)
    }

    public companion object {
        private val logger = LoggerFactory.getLogger(StorefrontGraphql::class.java)

        /** リソースの契約の SDL。 */
        public fun contractSdl(): String =
            checkNotNull(StorefrontGraphql::class.java.getResource("/contracts/graphql/storefront.v1.graphqls")) {
                "契約の SDL がリソースにありません"
            }.readText()

        internal fun badRequest(message: String): Map<String, Any> =
            GraphqlErrorBuilder
                .newError()
                .message(message)
                .errorType(StorefrontError)
                .extensions(mapOf("code" to ErrorCodes.BAD_REQUEST))
                .build()
                .toSpecification()
    }
}
