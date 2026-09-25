package io.eia.good.domain

import io.eia.shared.kernel.Result
import kotlin.jvm.JvmInline

@JvmInline
value class OrderId(
    val value: String,
) {
    // コメントや文字列中の runCatching { } や kotlin.Result は検出しない
    fun validate(): Result<OrderId, String> = if (value.isBlank()) Result.Err("runCatching { blank }") else Result.Ok(this)
}
