package io.eia.tools.contract.canonical

import io.eia.shared.canonical.sales.Order
import kotlinx.serialization.descriptors.SerialDescriptor

/** Canonical Model の型と、それを運ぶ Avro の名前付き型(完全修飾名)の対応。 */
data class CanonicalBinding(
    val descriptor: SerialDescriptor,
    val avroFullName: String,
)

/**
 * 一致検査の対象(ADR-0012)。Canonical Model を運ぶ Avro スキーマを追加したら、ここに対応を追加する。
 * 入れ子の型(OrderLine・Address・Money など)は親の検査で再帰的に検査される。
 */
object CanonicalBindings {
    val ALL: List<CanonicalBinding> =
        listOf(
            CanonicalBinding(Order.serializer().descriptor, "io.eia.events.sales.Order"),
        )
}
