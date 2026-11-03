package io.eia.platform.reliability

// 違反: gRPC は platform/grpc と services の adapters・app だけ(ADR-0042 §5)
import io.grpc.Status

class UsesGrpc(
    val status: Status,
)
