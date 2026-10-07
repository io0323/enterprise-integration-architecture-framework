package io.eia.order.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.order.adapters.inbound.rest.OrderApi
import io.eia.order.adapters.out.audit.ExposedOrderAuditTrail
import io.eia.order.adapters.out.outbox.OrderEventSchemas
import io.eia.order.adapters.out.outbox.OutboxOrderEvents
import io.eia.order.adapters.out.persistence.ExposedOrderRepository
import io.eia.order.adapters.out.persistence.ExposedTransactionBoundary
import io.eia.order.adapters.out.persistence.ExposedTransactionRunner
import io.eia.order.adapters.out.persistence.PostgresIdempotencyStore
import io.eia.order.adapters.out.persistence.UuidV7OrderIdGenerator
import io.eia.order.application.port.inbound.GetOrderUseCase
import io.eia.order.application.port.inbound.PlaceOrderUseCase
import io.eia.order.application.port.outbound.OrderAuditTrail
import io.eia.order.application.port.outbound.OrderEventOutbox
import io.eia.order.application.port.outbound.OrderIdGenerator
import io.eia.order.application.port.outbound.OrderRepository
import io.eia.order.application.port.outbound.TransactionRunner
import io.eia.order.application.usecase.GetOrderService
import io.eia.order.application.usecase.PlaceOrderService
import io.eia.platform.api.idempotency.IdempotencyConfig
import io.eia.platform.api.idempotency.IdempotencyHandler
import io.eia.platform.api.idempotency.IdempotencyStore
import io.eia.platform.api.idempotency.TransactionBoundary
import io.eia.platform.audit.AuditMetrics
import io.eia.platform.audit.anchor.AnchorCycle
import io.eia.platform.audit.anchor.AnchorMetrics
import io.eia.platform.audit.anchor.AnchorPublisher
import io.eia.platform.audit.anchor.S3AnchorStore
import io.eia.platform.audit.anchor.S3AnchorStoreConfig
import io.eia.platform.audit.anchor.ServiceName
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.outbox.Outbox
import io.eia.platform.outbox.OutboxEvents
import io.eia.platform.outbox.OutboxMetrics
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.security.jwt.JwtVerifier
import io.eia.platform.security.jwt.JwtVerifierConfig
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.kernel.getOrNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.toJavaDuration

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
    secrets: SecretProvider,
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
        // 監査の記録(ADR-0017)。追記の所要時間・ロックの待ち・失敗をメトリクスにする(A17-5)
        single { AuditLog(listener = AuditMetrics(runtime.meter)) }
        single<OrderAuditTrail> { ExposedOrderAuditTrail(get(), get()) }
        if (config.anchor.enabled) anchorBeans(config.anchor, runtime, secrets)
        eventBeans(config, runtime)
        single<PlaceOrderUseCase> { PlaceOrderService(get(), get(), get(), Clock.System, get(), get()) }
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

/** Schema Registry に問い合わせる HTTP クライアントの名前(起動時のスキーマ ID の解決だけに使う)。 */
internal val SCHEMA_REGISTRY_HTTP = named("schema-registry-http")

/**
 * 注文のイベントの発行(Outbox。ADR-0007・ADR-0025)。
 * 書き込むイベントのスキーマ ID は、起動時に [SchemaIdBook] で解決する(OrderServer が解決を繰り返し、`/health/ready` に反映する)。
 */
private fun Module.eventBeans(
    config: OrderConfig,
    runtime: ObservabilityRuntime,
) {
    single(SCHEMA_REGISTRY_HTTP) { HttpClient(CIO) }
    single {
        val registry = requireNotNull(config.schemaRegistryUrl) { "${OrderConfig.SCHEMA_REGISTRY_URL} が必要です" }
        SchemaIdBook(OrderEventSchemas.subjects, ApicurioRegistryClient(SchemaRegistryConfig(registry), get(SCHEMA_REGISTRY_HTTP)))
    }
    single { OrderEventSchemas.orderCreatedSerializer(get()) }
    single { Outbox(OutboxMetrics(runtime.meter)) }
    single { OutboxEvents(runtime, EVENT_SOURCE) }
    single<OrderEventOutbox> { OutboxOrderEvents(get(), get(), get(), get()) }
}

/** CloudEvents の `ce_source`(AsyncAPI の StandardHeaders の例と同じ)。 */
private const val EVENT_SOURCE = "/sales/order-service"

/**
 * 監査のアンカーの定期的な保存(ADR-0017 §5)。保存の前に、前回のアンカーからの差分を検証する([AnchorCycle])。
 * S3 には order の書込み用の identity(`eiaf-audit-order`。`anchors/order/` の下にだけ書ける)で接続する。
 */
private fun Module.anchorBeans(
    anchor: AuditAnchorConfig,
    runtime: ObservabilityRuntime,
    secrets: SecretProvider,
) {
    val service = requireNotNull(ServiceName.parse("order").getOrNull())
    single {
        S3AnchorStore(
            S3AnchorStoreConfig(
                endpoint = requireNotNull(anchor.endpoint) { "${AuditAnchorConfig.ENDPOINT} が必要です" },
                bucket = anchor.bucket,
                accessKeyName = AuditAnchorConfig.ACCESS_KEY,
                secretKeyName = AuditAnchorConfig.SECRET_KEY,
            ),
            secrets,
        )
    }
    single { AnchorMetrics(runtime.meter, anchor.interval.toJavaDuration()) }
    single {
        val retention = requireNotNull(anchor.retention) { "${AuditAnchorConfig.RETENTION} が必要です" }
        AnchorCycle(
            service,
            get<S3AnchorStore>(),
            AnchorPublisher(service, get<S3AnchorStore>(), retention.toJavaDuration()),
            get<AnchorMetrics>(),
        )
    }
}
