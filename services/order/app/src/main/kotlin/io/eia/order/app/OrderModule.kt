package io.eia.order.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.order.adapters.inbound.rest.OrderApi
import io.eia.order.adapters.out.persistence.ExposedOrderRepository
import io.eia.order.adapters.out.persistence.ExposedTransactionBoundary
import io.eia.order.adapters.out.persistence.ExposedTransactionRunner
import io.eia.order.adapters.out.persistence.PostgresIdempotencyStore
import io.eia.order.adapters.out.persistence.UuidV7OrderIdGenerator
import io.eia.order.application.port.inbound.GetOrderUseCase
import io.eia.order.application.port.inbound.PlaceOrderUseCase
import io.eia.order.application.port.outbound.OrderIdGenerator
import io.eia.order.application.port.outbound.OrderRepository
import io.eia.order.application.port.outbound.TransactionRunner
import io.eia.order.application.usecase.GetOrderService
import io.eia.order.application.usecase.PlaceOrderService
import io.eia.platform.api.idempotency.IdempotencyConfig
import io.eia.platform.api.idempotency.IdempotencyHandler
import io.eia.platform.api.idempotency.IdempotencyStore
import io.eia.platform.api.idempotency.TransactionBoundary
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.security.jwt.JwtVerifier
import io.eia.platform.security.jwt.JwtVerifierConfig
import io.eia.platform.security.secret.Secret
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * order-service の配線(Koin)。リクエストを処理するプロセス(serve)の部品だけで、所有者の資格情報は持たない(ADR-0024 §2)。
 *
 * 依存先ごとの `Resilience` は [ResilienceMetrics] の `resilience(...)` で作る(#8-c。Konsist の resilienceOnlyThroughMetrics)。
 * order-service には、P05 の時点で外への同期呼び出しがないため、まだ使う部品はない。
 */
internal fun orderModule(
    config: OrderConfig,
    appPassword: Secret,
    runtime: ObservabilityRuntime,
): Module =
    module {
        single { runtime }
        single { ResilienceMetrics(runtime.meter) }
        single(createdAtStart = true) {
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.dbUrl
                    username = config.appUser
                    password = appPassword.reveal()
                    poolName = "order-app"
                },
            )
        }
        single { Database.connect(get<HikariDataSource>()) }
        single<OrderRepository> { ExposedOrderRepository(get()) }
        single<TransactionRunner> { ExposedTransactionRunner(get()) }
        single<OrderIdGenerator> { UuidV7OrderIdGenerator() }
        single<PlaceOrderUseCase> { PlaceOrderService(get(), get(), get(), Clock.System) }
        single<GetOrderUseCase> { GetOrderService(get()) }
        single<IdempotencyStore> { PostgresIdempotencyStore(get()) }
        single { IdempotencyHandler(get(), IdempotencyConfig(lease = config.idempotencyLease)) }
        single<TransactionBoundary> { ExposedTransactionBoundary(get()) }
        single { OrderApi(get(), get(), get(), get()) }
        single {
            JwtVerifier(
                JwtVerifierConfig(
                    issuer = requireNotNull(config.issuer) { "OIDC_ISSUER が必要です" },
                    audience = config.audience,
                    jwksUri = requireNotNull(config.jwksUri) { "OIDC_JWKS_URI が必要です" },
                    // 呼び出し元のクライアントで Idempotency-Key の範囲を分けるため(ADR-0022 §3)
                    requireClientId = true,
                ),
                meter = runtime.meter,
            )
        }
    }
