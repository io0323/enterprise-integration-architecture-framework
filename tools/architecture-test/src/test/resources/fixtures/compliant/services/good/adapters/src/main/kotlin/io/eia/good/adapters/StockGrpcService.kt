package io.eia.good.adapters

// adapters は gRPC を使ってよい(チャネルとサーバは platform/grpc で作る)
import io.grpc.Status

class StockGrpcService(
    val status: Status,
)
