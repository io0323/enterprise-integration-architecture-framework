package io.eia.shared.canonical.catalog

import io.eia.shared.canonical.common.Validatable
import io.eia.shared.canonical.common.checkNotNegative
import io.eia.shared.canonical.common.validating
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.money.Money
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@Serializable
@JvmInline
public value class ProductId(
    public val value: String,
)

@Serializable
public enum class ProductStatus {
    ACTIVE,
    DISCONTINUED,
}

/** 商品。[unitPrice] は税抜の標準単価(ADR-0011 §4)。 */
@Serializable
public data class Product(
    val id: ProductId,
    val sku: String,
    val name: String,
    @Contextual val unitPrice: Money,
    val status: ProductStatus,
    val updatedAt: Instant,
) : Validatable<Product> {
    override fun validate(): Result<Product, ValidationError> =
        validating(this) {
            check(id.value.isNotBlank(), "id") { "必須です" }
            check(sku.isNotBlank(), "sku") { "必須です" }
            check(name.isNotBlank(), "name") { "必須です" }
            checkNotNegative("unitPrice", unitPrice)
        }
}
