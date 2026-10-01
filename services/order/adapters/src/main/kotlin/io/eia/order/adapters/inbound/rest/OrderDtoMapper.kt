package io.eia.order.adapters.inbound.rest

import io.eia.order.domain.AddressDraft
import io.eia.order.domain.Order
import io.eia.order.domain.OrderDraft
import io.eia.order.domain.OrderLineDraft
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.money.CurrencyResolver
import io.eia.shared.kernel.money.Money
import io.eia.shared.kernel.ok

/**
 * 契約の DTO と domain のモデルの変換(CLAUDE.md §4)。
 *
 * - 金額の文字列は、通貨を [CurrencyResolver] で解決してから `Money.parse` で読む。通貨の小数桁数を超える値は丸めずに違反にする
 *   (ADR-0011 §1)。違反の項目のパスは契約と同じ(例 `lines[0].unitPrice.amount`)。
 * - 金額以外の値域・業務整合の検証は domain(`Order.place`)が行う。
 */
internal object OrderDtoMapper {
    fun toDraft(
        dto: PlaceOrderRequestDto,
        currencies: CurrencyResolver,
    ): Result<OrderDraft, ValidationError> {
        val violations = mutableListOf<FieldViolation>()
        val lines =
            dto.lines.mapIndexed { index, line ->
                val price = money(line.unitPrice, "lines[$index].unitPrice", currencies, violations)
                price?.let { OrderLineDraft(line.productId, line.sku, line.quantity, it) }
            }
        if (violations.isNotEmpty()) return err(ValidationError(violations))
        val address = dto.shippingAddress
        return ok(
            OrderDraft(
                customerId = dto.customerId,
                lines = lines.filterNotNull(),
                shippingAddress =
                    AddressDraft(address.countryCode, address.postalCode, address.region, address.city, address.line1, address.line2),
            ),
        )
    }

    fun toDto(order: Order): OrderDto =
        OrderDto(
            id = order.id.value,
            customerId = order.customerId.value,
            status = order.status.name,
            orderedAt = order.orderedAt.toString(),
            lines =
                order.lines.map {
                    OrderLineDto(it.lineNumber, it.productId.value, it.sku.value, it.quantity, it.unitPrice.toDto(), it.lineAmount.toDto())
                },
            totalAmount = order.totalAmount.toDto(),
            shippingAddress =
                order.shippingAddress.let {
                    AddressDto(it.countryCode, it.postalCode, it.region, it.city, it.line1, it.line2)
                },
        )

    private fun Money.toDto(): MoneyDto = MoneyDto(toDecimalString(), currency.code)

    private fun money(
        dto: MoneyDto,
        path: String,
        currencies: CurrencyResolver,
        violations: MutableList<FieldViolation>,
    ): Money? {
        val currency = currencies.resolve(dto.currency)
        if (currency == null) {
            violations += FieldViolation("$path.currency", "扱えない通貨です")
            return null
        }
        return when (val parsed = Money.parse(dto.amount, currency)) {
            is Result.Ok -> {
                parsed.value
            }

            is Result.Err -> {
                violations += parsed.error.violations.map { FieldViolation("$path.${it.field}", it.reason) }
                null
            }
        }
    }
}
