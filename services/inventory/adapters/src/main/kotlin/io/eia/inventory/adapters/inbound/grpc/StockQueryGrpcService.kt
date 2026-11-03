package io.eia.inventory.adapters.inbound.grpc

import com.google.protobuf.Timestamp
import io.eia.inventory.application.port.inbound.GetStockLevelsUseCase
import io.eia.inventory.application.port.inbound.StockLevels
import io.eia.inventory.domain.StockSnapshot
import io.eia.platform.grpc.grpcResponse
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockRequest
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockResponse
import io.eia.platform.grpcstubs.inventory.v1.StockLevel
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpcKt
import io.eia.shared.kernel.map

/**
 * 在庫の照会の gRPC(INT-INVENTORY-003。contracts/proto/io/eia/inventory/v1/stock_query_service.proto)。
 * mTLS・JWT・スコープ・締め切りと伝搬は `GrpcServer` が適用する(ADR-0042)。ここは契約の型とユースケースの変換だけを行う。
 */
public class StockQueryGrpcService(
    private val getStockLevels: GetStockLevelsUseCase,
) : StockQueryServiceGrpcKt.StockQueryServiceCoroutineImplBase() {
    override suspend fun batchGetStock(request: BatchGetStockRequest): BatchGetStockResponse =
        grpcResponse { getStockLevels(request.skusList).map(::toResponse) }

    private fun toResponse(levels: StockLevels): BatchGetStockResponse =
        BatchGetStockResponse
            .newBuilder()
            .addAllLevels(levels.found.map(::toLevel))
            .addAllUnknownSkus(levels.unknownSkus)
            .build()

    private fun toLevel(snapshot: StockSnapshot): StockLevel =
        StockLevel
            .newBuilder()
            .setSku(snapshot.level.sku)
            .setAvailable(snapshot.level.available)
            .setUpdatedAt(
                Timestamp
                    .newBuilder()
                    .setSeconds(snapshot.updatedAt.epochSeconds)
                    .setNanos(snapshot.updatedAt.nanosecondsOfSecond),
            ).build()
}
