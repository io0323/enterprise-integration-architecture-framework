package io.eia.order.domain

import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.jvm.JvmInline

/** 識別子の長さの上限(契約の `orderId` の `maxLength` と同じ)。 */
public const val MAX_IDENTIFIER_LENGTH: Int = 64

/** 注文 ID。採番は application の Port(`OrderIdGenerator`)が行う。 */
@JvmInline
public value class OrderId private constructor(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        public fun parse(
            value: String,
            field: String = "orderId",
        ): Result<OrderId, ValidationError> = parseIdentifier(field, value, ::OrderId)
    }
}

/** 顧客 ID(顧客の参照キー。個人情報そのものではない)。 */
@JvmInline
public value class CustomerId private constructor(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        public fun parse(
            value: String,
            field: String = "customerId",
        ): Result<CustomerId, ValidationError> = parseIdentifier(field, value, ::CustomerId)
    }
}

@JvmInline
public value class ProductId private constructor(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        public fun parse(
            value: String,
            field: String = "productId",
        ): Result<ProductId, ValidationError> = parseIdentifier(field, value, ::ProductId)
    }
}

/** 在庫管理単位(Stock Keeping Unit)。 */
@JvmInline
public value class Sku private constructor(
    public val value: String,
) {
    override fun toString(): String = value

    public companion object {
        public fun parse(
            value: String,
            field: String = "sku",
        ): Result<Sku, ValidationError> = parseIdentifier(field, value, ::Sku)
    }
}

/** 空白だけでなく、[MAX_IDENTIFIER_LENGTH] 文字以内で、制御文字を含まない値。理由には値を含めない(CODING_STANDARDS)。 */
private fun <T> parseIdentifier(
    field: String,
    value: String,
    create: (String) -> T,
): Result<T, ValidationError> =
    when {
        value.isBlank() -> err(ValidationError.of(field, "必須です"))
        value.length > MAX_IDENTIFIER_LENGTH -> err(ValidationError.of(field, "$MAX_IDENTIFIER_LENGTH 文字以内です"))
        value.any { it.isISOControl() } -> err(ValidationError.of(field, "制御文字は使えません"))
        else -> ok(create(value))
    }

/** 違反を集めながら値を取り出す(すべての違反をまとめて返すため。ADR-0011 §5)。 */
internal class Violations {
    private val collected = mutableListOf<FieldViolation>()

    val isEmpty: Boolean get() = collected.isEmpty()

    fun add(
        field: String,
        reason: String,
    ) {
        collected += FieldViolation(field, reason)
    }

    fun <T> take(result: Result<T, ValidationError>): T? =
        when (result) {
            is Result.Ok -> {
                result.value
            }

            is Result.Err -> {
                collected += result.error.violations
                null
            }
        }

    fun toError(): ValidationError = ValidationError(collected.toList())
}
