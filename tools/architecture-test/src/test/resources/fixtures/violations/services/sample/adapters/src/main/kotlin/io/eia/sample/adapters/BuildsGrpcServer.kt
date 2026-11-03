package io.eia.sample.adapters

// 違反: サーバは platform/grpc の GrpcServer で作る(ADR-0042 §5)
import io.grpc.ServerBuilder

class BuildsGrpcServer(
    val builder: ServerBuilder<*>,
)
