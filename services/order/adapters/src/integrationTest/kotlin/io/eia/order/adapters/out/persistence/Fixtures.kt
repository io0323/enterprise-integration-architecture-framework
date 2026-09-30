@file:Suppress("MagicNumber") // テストデータの金額・件数・待ち時間、乱数のバイト数

package io.eia.order.adapters.out.persistence

import io.eia.order.domain.AddressDraft
import io.eia.order.domain.Order
import io.eia.order.domain.OrderDraft
import io.eia.order.domain.OrderId
import io.eia.order.domain.OrderLineDraft
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.money.Money
import io.kotest.assertions.fail
import kotlin.time.Instant

internal val ORDERED_AT: Instant = Instant.parse("2026-10-01T09:30:00.123456Z")

internal fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

internal fun <T, E> Result<T, E>.error(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

internal fun order(
    id: String = "ord-1",
    currency: Currency = Currency.USD,
): Order =
    Order
        .place(
            OrderId.parse(id).ok(),
            OrderDraft(
                customerId = "cust-1",
                lines =
                    listOf(
                        OrderLineDraft("prod-1", "SKU-1", 3, Money.ofMinor(1_050, currency)),
                        OrderLineDraft("prod-2", "SKU-2", 1, Money.ofMinor(99, currency)),
                    ),
                shippingAddress = AddressDraft("JP", "100-0001", null, "千代田区", "千代田 1-1", "3F"),
            ),
            ORDERED_AT,
        ).ok()
