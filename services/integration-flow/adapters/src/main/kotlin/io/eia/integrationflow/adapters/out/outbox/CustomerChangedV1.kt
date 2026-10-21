package io.eia.integrationflow.adapters.out.outbox

import io.eia.shared.canonical.sales.Customer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// Canonical の顧客のイベントの型(contracts/avro/sales/CustomerChanged.avsc。INT-SALES-007)。
// @SerialName は契約の record のフルネーム(ADR-0025 §1)。項目は Canonical Model の Customer と一致させる(ADR-0012)。

@Serializable
@SerialName("io.eia.events.sales.CustomerChanged")
public data class CustomerChangedV1(
    val customer: CustomerV1,
    val sourceSystem: String,
    val sourceVersion: Long,
)

@Serializable
@SerialName("io.eia.events.sales.Customer")
public data class CustomerV1(
    val id: String,
    val name: String,
    val email: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val billingAddress: AddressV1? = null,
) {
    // 個人情報を含むので ID 以外を伏せる
    override fun toString(): String = "CustomerV1(id=$id, ***)"

    public companion object {
        /** Canonical Model から作る(時刻は契約の精度のマイクロ秒に切り詰める)。 */
        public fun of(customer: Customer): CustomerV1 =
            CustomerV1(
                id = customer.id.value,
                name = customer.name,
                email = customer.email,
                createdAt = customer.createdAt.toMicros(),
                updatedAt = customer.updatedAt.toMicros(),
                billingAddress =
                    customer.billingAddress?.let {
                        AddressV1(it.countryCode, it.postalCode, it.city, it.line1, it.region, it.line2)
                    },
            )

        private fun Instant.toMicros(): Instant =
            Instant.fromEpochSeconds(
                epochSeconds,
                nanosecondsOfSecond / NANOS_PER_MICRO * NANOS_PER_MICRO,
            )

        private const val NANOS_PER_MICRO = 1_000
    }
}

@Serializable
@SerialName("io.eia.events.common.Address")
public data class AddressV1(
    val countryCode: String,
    val postalCode: String,
    val city: String,
    val line1: String,
    val region: String? = null,
    val line2: String? = null,
) {
    override fun toString(): String = "AddressV1(countryCode=$countryCode, ***)"
}
