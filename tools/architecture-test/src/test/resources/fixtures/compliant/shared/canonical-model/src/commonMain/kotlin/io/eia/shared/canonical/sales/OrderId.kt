package io.eia.shared.canonical.sales

import io.eia.shared.canonical.common.Address
import io.eia.shared.kernel.Result
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class OrderId(
    val value: String,
)

fun parse(value: String): Result<OrderId, Nothing> = Result.Ok(OrderId(value))
