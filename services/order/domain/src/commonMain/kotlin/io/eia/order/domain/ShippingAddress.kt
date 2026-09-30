package io.eia.order.domain

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * 配送先。個人情報なので [toString] では国コード以外を伏せる(CODING_STANDARDS「Canonical Model」)。
 *
 * - [countryCode]: ISO 3166-1 alpha-2(大文字 2 文字)
 * - [postalCode]・[city]・[line1]: 必須。[region]・[line2]: 任意
 * - 保存した値から作るときも [of] を使う(検証を通す)。
 */
public class ShippingAddress private constructor(
    public val countryCode: String,
    public val postalCode: String,
    public val region: String?,
    public val city: String,
    public val line1: String,
    public val line2: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is ShippingAddress &&
            countryCode == other.countryCode &&
            postalCode == other.postalCode &&
            region == other.region &&
            city == other.city &&
            line1 == other.line1 &&
            line2 == other.line2

    override fun hashCode(): Int = listOf(countryCode, postalCode, region, city, line1, line2).hashCode()

    override fun toString(): String = "ShippingAddress(countryCode=$countryCode, ***)"

    public companion object {
        public const val MAX_POSTAL_CODE_LENGTH: Int = 16
        public const val MAX_NAME_LENGTH: Int = 128
        public const val MAX_LINE_LENGTH: Int = 256
        private val COUNTRY_CODE = Regex("^[A-Z]{2}$")

        /** 違反をすべて集めて返す。項目のパスは [prefix] から始める(例 `shippingAddress.city`)。 */
        public fun of(
            draft: AddressDraft,
            prefix: String = "shippingAddress",
        ): Result<ShippingAddress, ValidationError> {
            val violations = Violations()
            with(draft) {
                if (!COUNTRY_CODE.matches(countryCode)) violations.add("$prefix.countryCode", "ISO 3166-1 alpha-2 の大文字 2 文字です")
                text(violations, "$prefix.postalCode", postalCode, MAX_POSTAL_CODE_LENGTH)
                region?.let { text(violations, "$prefix.region", it, MAX_NAME_LENGTH) }
                text(violations, "$prefix.city", city, MAX_NAME_LENGTH)
                text(violations, "$prefix.line1", line1, MAX_LINE_LENGTH)
                line2?.let { text(violations, "$prefix.line2", it, MAX_LINE_LENGTH) }
            }
            return if (violations.isEmpty) {
                ok(ShippingAddress(draft.countryCode, draft.postalCode, draft.region, draft.city, draft.line1, draft.line2))
            } else {
                err(violations.toError())
            }
        }

        private fun text(
            violations: Violations,
            field: String,
            value: String,
            maxLength: Int,
        ) {
            when {
                value.isBlank() -> violations.add(field, "必須です")
                value.length > maxLength -> violations.add(field, "$maxLength 文字以内です")
                value.any { it.isISOControl() } -> violations.add(field, "制御文字は使えません")
            }
        }
    }
}
