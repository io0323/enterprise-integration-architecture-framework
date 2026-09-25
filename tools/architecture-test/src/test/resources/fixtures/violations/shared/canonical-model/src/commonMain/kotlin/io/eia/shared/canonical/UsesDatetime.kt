package io.eia.shared.canonical

// 違反: canonical-model は kotlin.* / kotlinx.serialization.* / kernel 以外に依存しない
import io.eia.platform.observability.Tracer
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class Delivery(
    val date: LocalDate,
)
