package io.eia.shared.canonical.sales

import io.eia.shared.canonical.common.Address
import io.eia.shared.canonical.common.Validatable
import io.eia.shared.canonical.common.validating
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@Serializable
@JvmInline
public value class CustomerId(
    public val value: String,
)

/**
 * 顧客。
 *
 * 個人情報を含むため、[toString] は ID 以外を伏せる(ログへのペイロード出力禁止: CLAUDE.md §5)。
 */
@Serializable
public data class Customer(
    val id: CustomerId,
    val name: String,
    val email: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val billingAddress: Address? = null,
) : Validatable<Customer> {
    override fun validate(): Result<Customer, ValidationError> =
        validating(this) {
            check(id.value.isNotBlank(), "id") { "必須です" }
            check(name.isNotBlank(), "name") { "必須です" }
            check(EMAIL.matches(email), "email") { "メールアドレスの形式ではありません" }
            check(updatedAt >= createdAt, "updatedAt") { "createdAt 以降です" }
            billingAddress?.let { include("billingAddress", it.validate()) }
        }

    override fun toString(): String = "Customer(id=${id.value}, ***)"

    private companion object {
        // 形式の粗い検査のみ行う。到達可能性の確認は送信側の責務
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
