package io.eia.bffgraphql.adapters.graphql

import graphql.GraphQLContext
import graphql.GraphqlErrorBuilder
import graphql.execution.CoercedVariables
import graphql.execution.DataFetcherResult
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.PropertyDataFetcher
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeRuntimeWiring
import io.eia.bffgraphql.application.model.OrderLineView
import io.eia.bffgraphql.application.model.OrderView
import io.eia.bffgraphql.application.model.ShipmentView
import io.eia.bffgraphql.application.model.StockView
import io.eia.platform.security.jwt.VerifiedToken
import org.dataloader.DataLoader
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** 実行するスキーマ。契約の SDL から作る(ADR-0043 §1)。 */
internal class StorefrontSchema private constructor(
    val schema: GraphQLSchema,
    val policy: SdlPolicy,
) {
    companion object {
        /** 注文の `orders(ids:)` の結果。 */
        data class OrderBatchResult(
            val orders: List<OrderView>,
            val notFound: List<String>,
        )

        private const val ADDRESS_SCOPE = "sales.order:read-address"

        fun of(
            sdl: String,
            metrics: GraphqlMetrics,
        ): StorefrontSchema {
            val registry = SchemaParser().parse(sdl)
            val policy = SdlPolicy.of(registry)
            val fetchers: Map<FieldCoordinate, DataFetcher<*>> =
                mapOf(
                    FieldCoordinate("Query", "order") to
                        DataFetcher { env -> orders(env).load(checkNotNull(env.getArgument<String>("id"))) },
                    FieldCoordinate("Query", "orders") to DataFetcher(::orderBatch),
                    FieldCoordinate("Order", "shipment") to
                        DataFetcher { env -> shipments(env).load(checkNotNull(env.getSource<OrderView>()).id) },
                    FieldCoordinate("OrderLine", "stock") to
                        DataFetcher { env -> stock(env).load(checkNotNull(env.getSource<OrderLineView>()).sku) },
                )
            val wiring = RuntimeWiring.newRuntimeWiring().scalar(DATE_TIME).scalar(LONG)
            (fetchers.keys + policy.scopes.keys).groupBy { it.type }.forEach { (type, fields) ->
                val typeWiring = TypeRuntimeWiring.newTypeWiring(type)
                fields.forEach { coordinate ->
                    val base = fetchers[coordinate] ?: PropertyDataFetcher<Any>(coordinate.field)
                    val scope = policy.scopes[coordinate]
                    typeWiring.dataFetcher(coordinate.field, if (scope == null) base else guarded(coordinate, scope, base, metrics))
                }
                wiring.type(typeWiring)
            }
            return StorefrontSchema(SchemaGenerator().makeExecutableSchema(registry, wiring.build()), policy)
        }

        /**
         * 項目のスコープを確かめる(ADR-0043 §7)。足りなければ下流を呼ばず、null と FORBIDDEN を返す(ほかの項目は返す)。
         * 個人情報の項目を読んだら、値を残さずに数える。
         */
        private fun guarded(
            coordinate: FieldCoordinate,
            scope: String,
            delegate: DataFetcher<*>,
            metrics: GraphqlMetrics,
        ): DataFetcher<Any?> =
            DataFetcher { env ->
                val request = checkNotNull(env.graphQlContext.get<RequestContext>(RequestContext.KEY))
                if (request.token.hasScopes(listOf(scope))) {
                    if (scope == ADDRESS_SCOPE) request.sensitiveRead(coordinate, metrics)
                    delegate.get(env)
                } else {
                    DataFetcherResult
                        .newResult<Any?>()
                        .error(
                            GraphqlErrorBuilder
                                .newError(env)
                                .message("The field requires another scope")
                                .errorType(StorefrontError)
                                .extensions(mapOf("code" to ErrorCodes.FORBIDDEN, "scope" to scope))
                                .build(),
                        ).build()
                }
            }

        private fun orderBatch(env: DataFetchingEnvironment): CompletableFuture<OrderBatchResult> {
            val ids = env.getArgument<List<String>>("ids").orEmpty().distinct()
            return orders(env).loadMany(ids).thenApply { found ->
                OrderBatchResult(found.filterNotNull(), ids.filterIndexed { index, _ -> found[index] == null })
            }
        }

        private fun orders(env: DataFetchingEnvironment): DataLoader<String, OrderView?> =
            checkNotNull(env.getDataLoader<String, OrderView?>(Upstreams.ORDERS))

        private fun stock(env: DataFetchingEnvironment): DataLoader<String, StockView?> =
            checkNotNull(env.getDataLoader<String, StockView?>(Upstreams.STOCK))

        private fun shipments(env: DataFetchingEnvironment): DataLoader<String, ShipmentView?> =
            checkNotNull(env.getDataLoader<String, ShipmentView?>(Upstreams.SHIPMENTS))

        /** 出力だけの文字列のスカラー。引数には使わないので、入力は受け付けない。 */
        private fun outputOnly(
            name: String,
            description: String,
            serialize: (Any) -> Any,
        ): GraphQLScalarType =
            GraphQLScalarType
                .newScalar()
                .name(name)
                .description(description)
                .coercing(
                    object : Coercing<Any, Any> {
                        override fun serialize(
                            dataFetcherResult: Any,
                            graphQLContext: GraphQLContext,
                            locale: Locale,
                        ): Any = serialize(dataFetcherResult)

                        override fun parseValue(
                            input: Any,
                            graphQLContext: GraphQLContext,
                            locale: Locale,
                        ): Any = throw CoercingParseValueException("$name は入力に使えません")

                        override fun parseLiteral(
                            input: Value<*>,
                            variables: CoercedVariables,
                            graphQLContext: GraphQLContext,
                            locale: Locale,
                        ): Any = throw CoercingParseLiteralException("$name は入力に使えません")

                        override fun valueToLiteral(
                            input: Any,
                            graphQLContext: GraphQLContext,
                            locale: Locale,
                        ): Value<*> = StringValue(input.toString())
                    },
                ).build()

        private val DATE_TIME = outputOnly("DateTime", "RFC 3339 の日時") { it.toString() }
        private val LONG = outputOnly("Long", "64 ビットの整数") { (it as Number).toLong() }
    }
}

/** リクエストごとの値(`GraphQLContext` で運ぶ)。 */
internal class RequestContext(
    val token: VerifiedToken,
) {
    @Volatile
    var readSensitive: Boolean = false
        private set

    fun sensitiveRead(
        coordinate: FieldCoordinate,
        metrics: GraphqlMetrics,
    ) {
        readSensitive = true
        metrics.recordSensitiveRead(coordinate, token.clientId)
    }

    companion object {
        const val KEY = "eia.request"
    }
}
