package io.eia.sample.adapters

// 違反: リフレクションは使わない(ADR-0042 §5)
import io.grpc.protobuf.services.ProtoReflectionServiceV1

class UsesGrpcReflection(
    val reflection: ProtoReflectionServiceV1,
)
