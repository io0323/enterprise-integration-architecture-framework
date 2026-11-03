package io.eia.bffgraphql.adapters.outbound

import com.google.protobuf.Timestamp
import io.eia.bffgraphql.adapters.graphql.ComplexityRateLimiter
import io.eia.bffgraphql.adapters.graphql.GraphqlMetrics
import io.eia.bffgraphql.adapters.graphql.StorefrontGraphql
import io.eia.bffgraphql.application.model.AddressView
import io.eia.bffgraphql.application.model.MoneyView
import io.eia.bffgraphql.application.model.OrderLineView
import io.eia.bffgraphql.application.model.OrderStatusView
import io.eia.bffgraphql.application.model.OrderView
import io.eia.bffgraphql.application.model.ShipmentStatusView
import io.eia.bffgraphql.application.model.ShipmentView
import io.eia.bffgraphql.application.model.StockView
import io.eia.bffgraphql.application.port.outbound.OrderQueries
import io.eia.bffgraphql.application.usecase.LoadOrdersService
import io.eia.bffgraphql.application.usecase.LoadShipmentsService
import io.eia.bffgraphql.application.usecase.LoadStockLevelsService
import io.eia.platform.grpc.GrpcCallUnavailable
import io.eia.platform.grpc.GrpcCertificateFailure
import io.eia.platform.grpc.GrpcChannels
import io.eia.platform.grpc.GrpcClient
import io.eia.platform.grpc.GrpcServer
import io.eia.platform.grpc.GrpcServerConfig
import io.eia.platform.grpc.GrpcService
import io.eia.platform.grpc.GrpcTelemetry
import io.eia.platform.grpc.GrpcTokenSource
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockRequest
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockResponse
import io.eia.platform.grpcstubs.inventory.v1.StockLevel
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpcKt
import io.eia.platform.grpcstubs.shipping.v1.BatchGetShipmentsRequest
import io.eia.platform.grpcstubs.shipping.v1.BatchGetShipmentsResponse
import io.eia.platform.grpcstubs.shipping.v1.Shipment
import io.eia.platform.grpcstubs.shipping.v1.ShipmentQueryServiceGrpcKt
import io.eia.platform.grpcstubs.shipping.v1.ShipmentStatus
import io.eia.platform.security.jwt.VerifiedToken
import io.eia.platform.security.tls.ServerTls
import io.eia.platform.security.tls.TlsFiles
import io.eia.platform.security.tls.TlsVariables
import io.eia.platform.testsupport.TestPki
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.sdk.http.token.AccessToken
import io.grpc.ManagedChannel
import io.grpc.Status
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.api.OpenTelemetry
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SKU = "SKU-1"

/** 契約の proto のスタブで、実際の mTLS を通して読む(ADR-0042)。 */
class GrpcUpstreamQueriesSpec :
    FunSpec({
        val pki = TestPki()
        val telemetry = OpenTelemetry.noop().let { GrpcTelemetry(it, it.getTracer("test"), it.getMeter("test")) }

        fun tls(
            cert: TestPki.Issued,
            ca: TestPki = pki,
        ): ServerTls =
            checkNotNull(
                ServerTls
                    .load(TlsFiles(TlsVariables("TEST"), cert.certFile.toString(), cert.keyFile.toString(), ca.caFile.toString()))
                    .getOrNull(),
            )

        var stockFailures = 0
        val stockService =
            object : StockQueryServiceGrpcKt.StockQueryServiceCoroutineImplBase() {
                override suspend fun batchGetStock(request: BatchGetStockRequest): BatchGetStockResponse {
                    if (stockFailures-- > 0) throw Status.UNAVAILABLE.asException()
                    return BatchGetStockResponse
                        .newBuilder()
                        .addLevels(
                            StockLevel
                                .newBuilder()
                                .setSku(SKU)
                                .setAvailable(7)
                                .setUpdatedAt(Timestamp.newBuilder().setSeconds(1_800_000_000).setNanos(5_000_000)),
                        ).addUnknownSkus("SKU-X")
                        .build()
                }
            }
        val shipmentService =
            object : ShipmentQueryServiceGrpcKt.ShipmentQueryServiceCoroutineImplBase() {
                override suspend fun batchGetShipments(request: BatchGetShipmentsRequest): BatchGetShipmentsResponse =
                    BatchGetShipmentsResponse
                        .newBuilder()
                        .addShipments(
                            Shipment
                                .newBuilder()
                                .setOrderId("order-1")
                                .setStatus(ShipmentStatus.SHIPMENT_STATUS_SHIPPED)
                                .setShipmentId("ship-1")
                                .setShippedAt(Timestamp.newBuilder().setSeconds(1_800_000_000))
                                .setDestinationCountry("JP"),
                        ).addShipments(
                            Shipment
                                .newBuilder()
                                .setOrderId("order-2")
                                .setStatus(ShipmentStatus.SHIPMENT_STATUS_REJECTED),
                        ).addShipments(
                            Shipment
                                .newBuilder()
                                .setOrderId("order-3")
                                .setStatusValue(99),
                        ).build()
            }

        val server =
            GrpcServer.start(
                GrpcServerConfig(
                    port = 0,
                    tls = tls(pki.issue("upstream", listOf("localhost"))),
                    allowedClients = setOf("bff-graphql"),
                    verify = {
                        ok(
                            VerifiedToken(
                                "iss",
                                "sub",
                                "eiaf-bff-graphql",
                                listOf("aud"),
                                setOf(UpstreamScopes.STOCK, UpstreamScopes.SHIPMENT),
                                Clock.System.now(),
                                Clock.System.now() + 1.hours,
                                null,
                            ),
                        )
                    },
                    telemetry = telemetry,
                    services =
                        listOf(
                            GrpcService(stockService, setOf(UpstreamScopes.STOCK)),
                            GrpcService(shipmentService, setOf(UpstreamScopes.SHIPMENT)),
                        ),
                ),
            )
        val tokens =
            object : GrpcTokenSource {
                override suspend fun token(): Result<AccessToken, DomainError> = ok(AccessToken("t", null, emptySet()))

                override suspend fun invalidate(token: AccessToken) = Unit
            }
        val clientCert = pki.issue("bff", listOf("bff-graphql"))

        fun channel(): ManagedChannel = GrpcChannels.mutualTls("localhost:${server.port}", tls(clientCert))

        fun client(name: String) = GrpcClient(name, tokens, telemetry, fallbackDeadline = 30.seconds)

        fun resilience(name: String) =
            Resilience(
                name,
                ResilienceConfig(
                    attemptTimeout = 30.seconds,
                    retry = RetryPolicy(initialDelay = 1.milliseconds, maxAttempts = 2),
                    circuitBreaker = null,
                    retryBudget = null,
                ),
            )

        afterSpec {
            server.stop(0.seconds)
            pki.close()
        }

        test("在庫: 引当できる数をそのまま読み、時刻を RFC 3339 にする") {
            val channel = channel()
            val result = GrpcStockQueries(channel, client("inventory"), resilience("inventory")).find(setOf(SKU, "SKU-X"))
            channel.shutdownNow()

            result shouldBe ok(listOf(StockView(SKU, 7, "2027-01-15T08:00:00.005Z")))
        }

        test("在庫: 一時的な失敗は Resilience が再試行する") {
            stockFailures = 1
            val channel = channel()
            val result = GrpcStockQueries(channel, client("inventory"), resilience("inventory")).find(setOf(SKU))
            channel.shutdownNow()

            result.shouldBeInstanceOf<Result.Ok<*>>()
        }

        test("在庫: 再試行しても読めなければ Retryable のエラー") {
            stockFailures = 2
            val channel = channel()
            val result = GrpcStockQueries(channel, client("inventory"), resilience("inventory")).find(setOf(SKU))
            channel.shutdownNow()

            result.shouldBeInstanceOf<Result.Err<DomainError>>().error shouldBe GrpcCallUnavailable("inventory", Status.Code.UNAVAILABLE)
        }

        test("出荷: ないときの項目は null、一覧にない状態は UNKNOWN") {
            val channel = channel()
            val result = GrpcShipmentQueries(channel, client("shipping"), resilience("shipping")).find(setOf("order-1"))
            channel.shutdownNow()

            result shouldBe
                ok(
                    listOf(
                        ShipmentView("order-1", ShipmentStatusView.SHIPPED, "ship-1", "2027-01-15T08:00:00Z", "JP"),
                        ShipmentView("order-2", ShipmentStatusView.REJECTED, null, null, null),
                        ShipmentView("order-3", ShipmentStatusView.UNKNOWN, null, null, null),
                    ),
                )
        }

        test("信頼しない CA の相手は証明書のエラーで、再試行しない") {
            val other = TestPki("Other CA")
            val channel = GrpcChannels.mutualTls("localhost:${server.port}", tls(clientCert, other))
            val result = GrpcStockQueries(channel, client("inventory"), resilience("inventory")).find(setOf(SKU))
            channel.shutdownNow()
            other.close()

            result.shouldBeInstanceOf<Result.Err<DomainError>>().error.shouldBeInstanceOf<GrpcCertificateFailure>()
        }

        test("実際の gRPC で集約する: 注文 + 在庫 + 出荷。在庫の証明書のエラーでも部分応答を返す") {
            val money = MoneyView("10.00", "USD")
            val order =
                OrderView(
                    "order-1",
                    "customer-1",
                    OrderStatusView.PLACED,
                    "2026-11-03T00:00:00Z",
                    listOf(OrderLineView(1, "product-1", SKU, 2, money, money)),
                    money,
                    AddressView("JP", "100-0001", null, "Tokyo", "1-1", null),
                )
            val other = TestPki("Other CA")
            val untrusted = GrpcChannels.mutualTls("localhost:${server.port}", tls(clientCert, other))
            val trusted = channel()
            val engine =
                StorefrontGraphql(
                    StorefrontGraphql.contractSdl(),
                    LoadOrdersService(
                        object : OrderQueries {
                            override suspend fun find(ids: Set<String>) = ok(listOf(order))
                        },
                    ),
                    LoadStockLevelsService(GrpcStockQueries(untrusted, client("inventory"), resilience("inventory"))),
                    LoadShipmentsService(GrpcShipmentQueries(trusted, client("shipping"), resilience("shipping"))),
                    GraphqlMetrics(OpenTelemetry.noop().getMeter("test")),
                    ComplexityRateLimiter(1_000_000, 1_000_000),
                )
            val token =
                VerifiedToken(
                    "iss",
                    "sub",
                    "web",
                    listOf("bff-graphql"),
                    setOf("sales.order:read", UpstreamScopes.STOCK, UpstreamScopes.SHIPMENT),
                    Clock.System.now(),
                    Clock.System.now() + 1.hours,
                    null,
                )

            val result =
                engine.execute(
                    """{ order(id: "order-1") { shipment { shipmentId } lines { stock { available } } } }""",
                    null,
                    emptyMap(),
                    token,
                )
            untrusted.shutdownNow()
            trusted.shutdownNow()
            other.close()

            val data = checkNotNull(result.getData<Map<String, Map<String, Any?>>>()).getValue("order")
            data["shipment"] shouldBe mapOf("shipmentId" to "ship-1")
            data["lines"] shouldBe listOf(mapOf("stock" to null))
            val extensions = checkNotNull(result.errors.single().extensions)
            extensions["code"] shouldBe "UPSTREAM_ERROR"
            extensions["service"] shouldBe "inventory"
            extensions["retryable"] shouldBe false
        }
    })
