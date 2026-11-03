package io.eia.bffgraphql.adapters.graphql

import graphql.ErrorClassification
import graphql.ExecutionResult
import graphql.GraphqlErrorBuilder
import graphql.execution.AbortExecutionException
import graphql.execution.ExecutionContext
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters
import graphql.normalized.ExecutableNormalizedField
import graphql.normalized.ExecutableNormalizedOperation
import graphql.normalized.ExecutableNormalizedOperationFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 過大なクエリを、実行の前に拒否する(ROADMAP P13 の DoD。ADR-0043 §4)。データの取得の前に動くので、拒否したクエリは下流を呼ばない。
 *
 * **フラグメントをすべて展開し、変数を当てはめた後のクエリで数える**(graphql-java の正規化した操作)。
 * `QueryTraverser` は同じフラグメントを 1 回しか辿らないので、再利用したフラグメントで上限をすり抜けられる。使わない(`StorefrontGraphqlSpec`)。
 *
 * - `@maxItems`: リストの引数の要素数と、クエリ全体の注文の ID の数(重複を除く)。DataLoader が 1 回にまとめるので、別名で並べても下流の 1 回の上限を超えない。
 * - `maxAliases`: 別名の数。
 * - `maxDepth`: 項目の深さ。
 * - `maxComplexity`: [QueryCost] で数えた複雑度。
 * - 展開した項目の数が [MAX_EXPANDED_FIELDS] を超える(入れ子のフラグメントで指数的に増やす攻撃)は、展開を途中で止めて `maxComplexity` として拒否する。
 *
 * 上限以内のクエリは、複雑度を [rateLimiter] から消費する(ADR-0043 §9)。足りなければ実行せず、[RateLimited] のエラーで返す(ルートが 429 にする)。
 */
internal class QueryLimitInstrumentation(
    private val policy: SdlPolicy,
    private val rateLimiter: ComplexityRateLimiter,
    private val onRejected: (String) -> Unit,
) : SimplePerformantInstrumentation() {
    override fun beginExecuteOperation(
        parameters: InstrumentationExecuteOperationParameters,
        state: InstrumentationState?,
    ): InstrumentationContext<ExecutionResult> {
        val operation =
            try {
                normalize(parameters.executionContext)
            } catch (_: AbortExecutionException) {
                reject(LIMIT_COMPLEXITY)
            }
        exceeded(operation)?.let(::reject)
        val request = checkNotNull(parameters.executionContext.graphQLContext.get<RequestContext>(RequestContext.KEY))
        rateLimiter.acquire(request.client, operation.topLevelFields.sumOf(QueryCost::of))?.let { wait ->
            onRejected(LIMIT_RATE)
            throw AbortExecutionException(listOf(RateLimited.error(wait)))
        }
        return SimpleInstrumentationContext.noOp()
    }

    private fun reject(limit: String): Nothing {
        onRejected(limit)
        throw AbortExecutionException(listOf(limitError(limit)))
    }

    private fun normalize(context: ExecutionContext): ExecutableNormalizedOperation =
        ExecutableNormalizedOperationFactory.createExecutableNormalizedOperation(
            context.graphQLSchema,
            context.document,
            context.executionInput.operationName,
            context.coercedVariables,
            ExecutableNormalizedOperationFactory.Options
                .defaultOptions()
                .maxFieldsCount(MAX_EXPANDED_FIELDS),
        )

    private fun exceeded(operation: ExecutableNormalizedOperation): String? {
        val limits = policy.limits
        // traverseSubTree は自分自身を含まない
        val fields =
            buildList {
                operation.topLevelFields.forEach { top ->
                    add(top)
                    top.traverseSubTree(::add)
                }
            }
        return when {
            fields.any(::hasOversizedArgument) || tooManyOrderIds(operation) -> "maxItems"
            fields.count { it.alias != null } > limits.maxAliases -> "maxAliases"
            operation.operationDepth > limits.maxDepth -> "maxDepth"
            operation.topLevelFields.sumOf(QueryCost::of) > limits.maxComplexity -> LIMIT_COMPLEXITY
            else -> null
        }
    }

    private fun hasOversizedArgument(field: ExecutableNormalizedField): Boolean {
        val coordinate = FieldCoordinate(field.singleObjectTypeName, field.fieldName)
        return field.resolvedArguments.any { (name, value) ->
            val limit = policy.maxItems[coordinate to name]
            limit != null && value is Collection<*> && value.size > limit
        }
    }

    /** 別名で並べた `order`・`orders` の ID は、DataLoader が 1 回の呼び出しにまとめる。その合計も下流の上限まで。 */
    private fun tooManyOrderIds(operation: ExecutableNormalizedOperation): Boolean {
        val limit = policy.maxItems[QueryCost.ORDERS to QueryCost.ORDERS_IDS] ?: return false
        val ids =
            operation.topLevelFields.flatMapTo(mutableSetOf()) { field ->
                field.resolvedArguments.values.flatMap { value ->
                    when (value) {
                        is Collection<*> -> value
                        else -> listOf(value)
                    }
                }
            }
        return ids.size > limit
    }

    private fun limitError(limit: String) =
        GraphqlErrorBuilder
            .newError()
            .message("The query exceeds a limit: $limit")
            .errorType(LimitExceeded)
            .extensions(mapOf("code" to ErrorCodes.QUERY_LIMIT_EXCEEDED, "limit" to limit))
            .build()

    private object LimitExceeded : ErrorClassification

    private companion object {
        const val LIMIT_COMPLEXITY = "maxComplexity"
        const val LIMIT_RATE = "complexityRate"

        /**
         * 展開した項目の数の上限。正当な最大のクエリ(全部の項目を読む)でも数百なので、十分に大きい。
         * 複雑度に数えない項目(スカラー)だけで増やされても、ここで止まる。
         */
        const val MAX_EXPANDED_FIELDS = 2000
    }
}

/** 複雑度の枠が足りない(ルートが 429 + `Retry-After` にする。ADR-0043 §9)。 */
internal object RateLimited : ErrorClassification {
    private const val RETRY_AFTER_MILLIS = "retryAfterMillis"

    fun error(wait: Duration) =
        GraphqlErrorBuilder
            .newError()
            .message("The complexity rate limit was exceeded")
            .errorType(this)
            .extensions(mapOf("code" to ErrorCodes.RATE_LIMITED, RETRY_AFTER_MILLIS to wait.inWholeMilliseconds))
            .build()

    /** [result] が枠の不足で拒否されたなら、待つ時間。 */
    fun retryAfter(result: ExecutionResult): Duration? =
        result.errors
            .firstOrNull { it.extensions?.get("code") == ErrorCodes.RATE_LIMITED }
            ?.let { (it.extensions?.get(RETRY_AFTER_MILLIS) as Long).milliseconds }
}

/**
 * 複雑度の数え方(ADR-0043 §4)。**下流を呼ぶ項目**と、その件数で数える。スカラーと、データを持っているだけの型(`Money`・`Address`)は数えない。
 *
 * | 項目 | 複雑度 |
 * |---|---|
 * | `Query.order` | 1 + 子 |
 * | `Query.orders(ids:)` | 件数 × (1 + 子) |
 * | `Order.lines` | [ASSUMED_LINES] × 子(実際の明細の数は実行するまで分からない) |
 * | `Order.shipment`・`OrderLine.stock` | 1 + 子 |
 * | そのほか | 子 |
 *
 * 正当な最大のクエリ(`orders` 20 件で、明細の在庫と出荷を読む)は 20 × (1 + 5 + 1) = 140。
 * 同じ大きさのクエリを別名で 2 回並べると 280 で、上限(200)を超える。
 */
internal object QueryCost {
    /** 実行の前には明細の数が分からないので、1 注文の明細の数をこの値と仮定する。 */
    const val ASSUMED_LINES = 5
    const val ORDERS_IDS = "ids"
    val ORDERS = FieldCoordinate("Query", "orders")
    private val ORDER = FieldCoordinate("Query", "order")
    private val LINES = FieldCoordinate("Order", "lines")
    private val SHIPMENT = FieldCoordinate("Order", "shipment")
    private val STOCK = FieldCoordinate("OrderLine", "stock")

    fun of(field: ExecutableNormalizedField): Int {
        val children = field.children.sumOf(::of)
        return when (FieldCoordinate(field.singleObjectTypeName, field.fieldName)) {
            ORDER, SHIPMENT, STOCK -> 1 + children
            ORDERS -> ((field.resolvedArguments[ORDERS_IDS] as? Collection<*>)?.size ?: 1) * (1 + children)
            LINES -> ASSUMED_LINES * children
            else -> children
        }
    }
}
