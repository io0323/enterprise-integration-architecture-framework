package io.eia.integrationflow.adapters.canonical

import io.eia.integrationflow.domain.MappedRecord
import io.eia.integrationflow.domain.MappedValue
import io.eia.shared.canonical.common.CanonicalCodec
import io.eia.shared.canonical.sales.Customer
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Flow の `canonical` の名前と Canonical Model の型(flow.v1.schema.json の enum と揃える。ADR-0036 Consequences)。
 * 写した結果([MappedRecord])は、ここで Canonical の型に変える(domain と application は Canonical に依存しない。ADR-0010 Decision 7)。
 * 変換は Canonical の受信点と同じく [CanonicalCodec.decode](構造と値域の検証)を通す。
 */
public object CanonicalTypes {
    public const val CUSTOMER: String = "sales.Customer"

    /** 起動時の検証に使う型の形(項目の名前・必須・入れ子)。 */
    public val descriptors: Map<String, SerialDescriptor> = mapOf(CUSTOMER to serializer<Customer>().descriptor)

    private val codec = CanonicalCodec()

    public fun toCustomer(mapped: MappedRecord): Result<Customer, DomainError> =
        codec.decode(serializer<Customer>(), json(mapped).toString())

    /** 値のない項目は書かない(Canonical の既定値・必須の検査に任せる)。 */
    internal fun json(mapped: MappedRecord): JsonObject =
        JsonObject(
            mapped.fields
                .mapNotNull { (name, value) -> element(value)?.let { name to it } }
                .toMap(),
        )

    private fun element(value: MappedValue): JsonElement? =
        when (value) {
            is MappedValue.Text -> JsonPrimitive(value.value)
            is MappedValue.Time -> JsonPrimitive(value.value.toString())
            is MappedValue.Nested -> json(value.record)
            MappedValue.Absent -> null
        }
}
