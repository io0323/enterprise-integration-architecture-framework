package io.eia.saasmock.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/** CRM の JSON。契約で null を許さない項目(`region`・`line2`)は、値がなければ書かない。未知の項目は無視する。 */
internal val CrmJson: Json =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

/**
 * CRM の顧客(外部の契約 contracts/external/crm-api.v1.yaml の Customer)。項目はベンダーの形(snake_case)。
 * [loyaltyTier] と [links] は、契約の写しにない(こちらが使わない)ベンダーの項目。受け手の Tolerant Reader を確かめるために返す。
 */
@Serializable
data class CrmCustomer(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val email: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val version: Long,
    val address: CrmAddress? = null,
    @SerialName("loyalty_tier") val loyaltyTier: String = "standard",
    @SerialName("_links") val links: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "CrmCustomer(id=$id, version=$version, ***)"
}

@Serializable
data class CrmAddress(
    val country: String,
    @SerialName("postal_code") val postalCode: String,
    val region: String? = null,
    val city: String,
    val line1: String,
    val line2: String? = null,
)

/** 一覧の 1 ページ。 */
@Serializable
data class CrmCustomerPage(
    val data: List<CrmCustomer>,
    @SerialName("next_cursor") val nextCursor: String?,
)

/** 管理の API(SaaS の画面の代わり)で作る・変える顧客の項目。変えるときは書いた項目だけを変える。 */
@Serializable
data class CustomerInput(
    val id: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val email: String? = null,
    val address: CrmAddress? = null,
)

/** 管理の API で送る Webhook の種類(例 `customer.reactivated`)。 */
@Serializable
internal data class EventInput(
    val type: String,
)

/** 管理の API で Rate Limit を変える(`<回数>/<期間>`)。 */
@Serializable
internal data class RateLimitInput(
    val limit: String,
)

/** 顧客の置き場(メモリの上。スレッドの間で安全)。変更のたびに版を 1 増やす。 */
class CrmCustomers(
    private val clock: Clock = Clock.System,
) {
    private val customers = sortedMapOf<String, CrmCustomer>()

    @Synchronized
    fun create(input: CustomerInput): CrmCustomer? {
        val id = input.id ?: "cus_${UUID.randomUUID().toString().replace("-", "").take(ID_LENGTH)}"
        if (id in customers || input.displayName.isNullOrBlank() || input.email.isNullOrBlank()) return null
        val now = clock.now().toString()
        return CrmCustomer(id, input.displayName, input.email, now, now, 1, input.address, links = links(id)).also { customers[id] = it }
    }

    @Synchronized
    fun update(
        id: String,
        input: CustomerInput,
    ): CrmCustomer? {
        val current = customers[id] ?: return null
        return current
            .copy(
                displayName = input.displayName ?: current.displayName,
                email = input.email ?: current.email,
                address = input.address ?: current.address,
                updatedAt = clock.now().toString(),
                version = current.version + 1,
            ).also { customers[id] = it }
    }

    @Synchronized
    fun get(id: String): CrmCustomer? = customers[id]

    /** ID の順の 1 ページ。[cursor] は前のページの最後の ID を不透明にしたもの。[updatedSince] の後に変わったものだけ。 */
    @Synchronized
    fun page(
        cursor: String?,
        limit: Int,
        updatedSince: Instant?,
    ): CrmCustomerPage {
        val after = cursor?.let(::decodeCursor)
        val matching =
            customers.values
                .asSequence()
                .filter { after == null || it.id > after }
                .filter { updatedSince == null || Instant.parse(it.updatedAt) > updatedSince }
                .take(limit + 1)
                .toList()
        val data = matching.take(limit)
        return CrmCustomerPage(data, if (matching.size > limit) encodeCursor(data.last().id) else null)
    }

    private fun links(id: String) = mapOf("self" to "/v1/customers/$id")

    companion object {
        private const val ID_LENGTH = 12

        fun encodeCursor(id: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray())

        /** 読めないカーソルは null(最初のページとしては扱わない。呼び出し側が 400 にする)。 */
        fun decodeCursor(cursor: String): String? =
            try {
                String(Base64.getUrlDecoder().decode(cursor))
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
