package io.eia.shipping.adapters.inbound.grpc

import com.google.protobuf.Timestamp
import io.eia.platform.grpc.grpcResponse
import io.eia.platform.grpcstubs.shipping.v1.BatchGetShipmentsRequest
import io.eia.platform.grpcstubs.shipping.v1.BatchGetShipmentsResponse
import io.eia.platform.grpcstubs.shipping.v1.ShipmentQueryServiceGrpcKt
import io.eia.shared.kernel.map
import io.eia.shipping.application.port.inbound.GetShipmentsUseCase
import io.eia.shipping.domain.Shipment
import io.eia.shipping.domain.ShipmentStatus
import io.eia.platform.grpcstubs.shipping.v1.Shipment as ShipmentContract
import io.eia.platform.grpcstubs.shipping.v1.ShipmentStatus as ShipmentStatusContract

/**
 * 出荷の照会の gRPC(INT-SHIPPING-003。contracts/proto/io/eia/shipping/v1/shipment_query_service.proto)。
 * mTLS・JWT・スコープ・締め切りと伝搬は `GrpcServer` が適用する(ADR-0042)。ここは契約の型とユースケースの変換だけを行う。
 */
public class ShipmentQueryGrpcService(
    private val getShipments: GetShipmentsUseCase,
) : ShipmentQueryServiceGrpcKt.ShipmentQueryServiceCoroutineImplBase() {
    override suspend fun batchGetShipments(request: BatchGetShipmentsRequest): BatchGetShipmentsResponse =
        grpcResponse {
            getShipments(request.orderIdsList).map { shipments ->
                BatchGetShipmentsResponse.newBuilder().addAllShipments(shipments.map(::toContract)).build()
            }
        }

    private fun toContract(shipment: Shipment): ShipmentContract {
        val builder =
            ShipmentContract
                .newBuilder()
                .setOrderId(shipment.orderId)
                .setStatus(statusOf(shipment.status))
        if (shipment.status == ShipmentStatus.SHIPPED) {
            shipment.shipmentId?.let(builder::setShipmentId)
            shipment.destination?.let { builder.setDestinationCountry(it.countryCode) }
            shipment.shippedAt?.let {
                builder.setShippedAt(Timestamp.newBuilder().setSeconds(it.epochSeconds).setNanos(it.nanosecondsOfSecond))
            }
        }
        return builder.build()
    }

    private fun statusOf(status: ShipmentStatus): ShipmentStatusContract =
        when (status) {
            ShipmentStatus.SHIPPED -> ShipmentStatusContract.SHIPMENT_STATUS_SHIPPED

            ShipmentStatus.REJECTED -> ShipmentStatusContract.SHIPMENT_STATUS_REJECTED

            // ユースケースが除くので届かない。契約にない内部の印を外に出さない
            ShipmentStatus.CANCELLED_BEFORE_ARRANGEMENT -> ShipmentStatusContract.SHIPMENT_STATUS_UNSPECIFIED
        }
}
