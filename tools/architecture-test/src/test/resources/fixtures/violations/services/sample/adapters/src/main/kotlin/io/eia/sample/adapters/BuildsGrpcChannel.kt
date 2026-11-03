package io.eia.sample.adapters

// 違反: チャネルは platform/grpc の GrpcChannels で作る(ADR-0042 §5)
import io.grpc.ManagedChannelBuilder

class BuildsGrpcChannel(
    val builder: ManagedChannelBuilder<*>,
)
