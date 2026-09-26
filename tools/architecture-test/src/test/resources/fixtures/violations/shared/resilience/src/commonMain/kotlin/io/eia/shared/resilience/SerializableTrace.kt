package io.eia.shared.resilience

// 違反: shared/resilience の許可リスト外(kotlinx.serialization)
import kotlinx.serialization.Serializable

@Serializable
data class SerializableTrace(val value: String)
