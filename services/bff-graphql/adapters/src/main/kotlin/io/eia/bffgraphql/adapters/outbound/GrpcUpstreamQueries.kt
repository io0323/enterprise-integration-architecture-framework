package io.eia.bffgraphql.adapters.outbound

import com.google.protobuf.Timestamp
import io.eia.bffgraphql.application.model.ShipmentStatusView
import io.eia.bffgraphql.application.model.ShipmentView
import io.eia.bffgraphql.application.model.StockView
import io.eia.bffgraphql.application.port.outbound.ShipmentQueries
import io.eia.bffgraphql.application.port.outbound.StockQueries
import io.eia.platform.grpc.GrpcClient
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockRequest
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpc
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpcKt.StockQueryServiceCoroutineStub
import io.eia.platform.grpcstubs.shipping.v1.BatchGetShipmentsRequest
import io.eia.platform.grpcstubs.shipping.v1.Shipment
import io.eia.platform.grpcstubs.shipping.v1.ShipmentQueryServiceGrpc
import io.eia.platform.grpcstubs.shipping.v1.ShipmentQueryServiceGrpcKt.ShipmentQueryServiceCoroutineStub
import io.eia.platform.grpcstubs.shipping.v1.ShipmentStatus
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.map
import io.eia.shared.resilience.Resilience
import io.grpc.Channel
import kotlin.time.Instant

/**
 * 在庫を inventory-service の gRPC でまとめて読む(INT-INVENTORY-003。ADR-0042)。
 * [resilience] は app で `ResilienceMetrics` から作る。締め切りは `GrpcClient` が gRPC の deadline に引き継ぐ。
 * チャネルは app で `GrpcChannels.mutualTls` で作る(mTLS。クライアント証明書の SAN は `bff-graphql`)。
 */
public class GrpcStockQueries(
    channel: Channel,
    private val client: GrpcClient,
    private val resilience: Resilience,
) : StockQueries {
    private val stub = StockQueryServiceCoroutineStub(channel)

    override suspend fun find(skus: Set<String>): Result<List<StockView>, DomainError> =
        resilience
            .execute {
                val request = BatchGetStockRequest.newBuilder().addAllSkus(skus).build()
                client.call(stub, StockQueryServiceGrpc.getBatchGetStockMethod()) { metadata -> batchGetStock(request, metadata) }
            }.map { response ->
                response.levelsList.map { StockView(it.sku, it.available, it.updatedAt.toRfc3339()) }
            }
}

/**
 * 出荷を shipping-service の gRPC でまとめて読む(INT-SHIPPING-003。ADR-0042)。一覧にない状態は `UNKNOWN` にする(Tolerant Reader)。
 * 出荷の ID・時刻・国は、ないとき(空の文字列・未設定の時刻)に null にする。
 */
public class GrpcShipmentQueries(
    channel: Channel,
    private val client: GrpcClient,
    private val resilience: Resilience,
) : ShipmentQueries {
    private val stub = ShipmentQueryServiceCoroutineStub(channel)

    override suspend fun find(orderIds: Set<String>): Result<List<ShipmentView>, DomainError> =
        resilience
            .execute {
                val request = BatchGetShipmentsRequest.newBuilder().addAllOrderIds(orderIds).build()
                client.call(
                    stub,
                    ShipmentQueryServiceGrpc.getBatchGetShipmentsMethod(),
                ) { metadata -> batchGetShipments(request, metadata) }
            }.map { response -> response.shipmentsList.map(::toView) }

    private fun toView(shipment: Shipment): ShipmentView =
        ShipmentView(
            orderId = shipment.orderId,
            status =
                when (shipment.status) {
                    ShipmentStatus.SHIPMENT_STATUS_SHIPPED -> ShipmentStatusView.SHIPPED
                    ShipmentStatus.SHIPMENT_STATUS_REJECTED -> ShipmentStatusView.REJECTED
                    else -> ShipmentStatusView.UNKNOWN
                },
            shipmentId = shipment.shipmentId.ifEmpty { null },
            shippedAt = if (shipment.hasShippedAt()) shipment.shippedAt.toRfc3339() else null,
            destinationCountry = shipment.destinationCountry.ifEmpty { null },
        )
}

private fun Timestamp.toRfc3339(): String = Instant.fromEpochSeconds(seconds, nanos).toString()

/** 下流のスコープ(`eiaf-bff-graphql` が要求する。すべて読み取り。ADR-0043 §7)。 */
public object UpstreamScopes {
    public const val STOCK: String = "inventory.stock:read"
    public const val SHIPMENT: String = "shipping.shipment:read"
}
