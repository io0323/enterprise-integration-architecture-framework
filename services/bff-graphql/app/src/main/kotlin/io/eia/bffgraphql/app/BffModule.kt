package io.eia.bffgraphql.app

import io.eia.bffgraphql.adapters.graphql.ComplexityRateLimiter
import io.eia.bffgraphql.adapters.graphql.GraphqlMetrics
import io.eia.bffgraphql.adapters.graphql.StorefrontGraphql
import io.eia.bffgraphql.adapters.graphql.UpstreamBudget
import io.eia.bffgraphql.adapters.outbound.GrpcShipmentQueries
import io.eia.bffgraphql.adapters.outbound.GrpcStockQueries
import io.eia.bffgraphql.adapters.outbound.HttpOrderQueries
import io.eia.bffgraphql.adapters.outbound.OrderQuotaMetrics
import io.eia.bffgraphql.adapters.outbound.UpstreamScopes
import io.eia.bffgraphql.application.usecase.LoadOrdersService
import io.eia.bffgraphql.application.usecase.LoadShipmentsService
import io.eia.bffgraphql.application.usecase.LoadStockLevelsService
import io.eia.platform.grpc.GrpcChannels
import io.eia.platform.grpc.GrpcClient
import io.eia.platform.grpc.GrpcTelemetry
import io.eia.platform.grpc.asGrpcTokenSource
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.ktor.client.ClientObservability
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.security.jwt.JwtVerifier
import io.eia.platform.security.jwt.JwtVerifierConfig
import io.eia.platform.security.secret.SecretProvider
import io.eia.platform.security.tls.ServerTls
import io.eia.platform.security.token.ClientCredentialsConfig
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.grpc.ManagedChannel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Duration.Companion.milliseconds

/** 1 回の試行の上限。注文は予算の配分(2 秒)の中で、在庫と出荷は残りの時間の中で打ち切る(ADR-0043 §6)。 */
private val ATTEMPT_TIMEOUT = 1_500.milliseconds

internal val INVENTORY_CHANNEL = named("inventory-channel")
internal val SHIPPING_CHANNEL = named("shipping-channel")

private val TOKEN_RESILIENCE = named("token-resilience")

/** 下流ごとに、必要なスコープとその下流の `aud` だけのトークンを取る(ADR-0043 §7)。 */
private fun Scope.tokens(
    config: BffConfig,
    secrets: SecretProvider,
    vararg scopes: String,
) = ClientCredentialsTokenProvider(
    ClientCredentialsConfig(config.tokenUrl, config.clientId, BffConfig.CLIENT_SECRET, scopes.toSet()),
    get<HttpClient>(),
    secrets,
    resilience = get<Resilience>(TOKEN_RESILIENCE),
)

private fun Scope.resilience(dependency: String): Resilience =
    get<ResilienceMetrics>().resilience(dependency, ResilienceConfig(attemptTimeout = ATTEMPT_TIMEOUT))

internal fun bffModule(
    config: BffConfig,
    tls: ServerTls,
    runtime: ObservabilityRuntime,
    secrets: SecretProvider,
): Module =
    module {
        single { runtime }
        single { HttpClient(CIO) { install(ClientObservability) { this.runtime = runtime } } }
        single { ResilienceMetrics(runtime.meter) }
        single { GrpcTelemetry.of(runtime) }
        single {
            JwtVerifier(
                JwtVerifierConfig(
                    issuer = config.issuer,
                    audience = config.audience,
                    jwksUri = config.jwksUri,
                    // 複雑度の枠のキーにする(ADR-0043 §9)
                    requireClientId = true,
                ),
                meter = runtime.meter,
            )
        }
        single<ManagedChannel>(INVENTORY_CHANNEL) { GrpcChannels.mutualTls(config.inventoryTarget, tls) }
        single<ManagedChannel>(SHIPPING_CHANNEL) { GrpcChannels.mutualTls(config.shippingTarget, tls) }

        single(TOKEN_RESILIENCE) {
            get<ResilienceMetrics>().resilience(
                ClientCredentialsTokenProvider.DEFAULT_RESILIENCE_NAME,
                ClientCredentialsTokenProvider.DEFAULT_RESILIENCE,
            )
        }
        single {
            val stockTokens = tokens(config, secrets, UpstreamScopes.STOCK, "inventory-grpc-audience")
            val shipmentTokens = tokens(config, secrets, UpstreamScopes.SHIPMENT, "shipping-grpc-audience")
            StorefrontGraphql(
                StorefrontGraphql.contractSdl(),
                LoadOrdersService(
                    HttpOrderQueries(
                        get(),
                        config.gatewayUrl,
                        tokens(config, secrets, HttpOrderQueries.SCOPE, "order-api-audience"),
                        resilience("order-api"),
                        OrderQuotaMetrics(runtime.meter),
                    ),
                ),
                LoadStockLevelsService(
                    GrpcStockQueries(
                        get<ManagedChannel>(INVENTORY_CHANNEL),
                        GrpcClient("inventory", stockTokens.asGrpcTokenSource(), get()),
                        resilience("inventory"),
                    ),
                ),
                LoadShipmentsService(
                    GrpcShipmentQueries(
                        get<ManagedChannel>(SHIPPING_CHANNEL),
                        GrpcClient("shipping", shipmentTokens.asGrpcTokenSource(), get()),
                        resilience("shipping"),
                    ),
                ),
                GraphqlMetrics(runtime.meter),
                ComplexityRateLimiter(config.complexityCapacity, config.complexityRefillPerSecond),
                UpstreamBudget(total = config.requestBudget),
                config.introspectionEnabled,
            )
        }
    }
