package io.eia.shared.canonical.common

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import kotlinx.serialization.Serializable

/**
 * 住所。国コードは ISO 3166-1 alpha-2。
 *
 * 個人情報を含むため、[toString] は国コード以外を伏せる(ログへのペイロード出力禁止: CLAUDE.md §5)。
 */
@Serializable
public data class Address(
    val countryCode: String,
    val postalCode: String,
    val city: String,
    val line1: String,
    val region: String? = null,
    val line2: String? = null,
) : Validatable<Address> {
    override fun validate(): Result<Address, ValidationError> =
        validating(this) {
            check(COUNTRY_CODE.matches(countryCode), "countryCode") { "ISO 3166-1 alpha-2 の国コードです" }
            check(postalCode.isNotBlank(), "postalCode") { "必須です" }
            check(city.isNotBlank(), "city") { "必須です" }
            check(line1.isNotBlank(), "line1") { "必須です" }
        }

    override fun toString(): String = "Address(countryCode=$countryCode, ***)"

    private companion object {
        private val COUNTRY_CODE = Regex("^[A-Z]{2}$")
    }
}
