package io.eia.platform.reliability

// 違反: 完全修飾名でも同じ(ADR-0042 §5)
class UsesGrpcQualified(
    val status: io.grpc.Status,
)
