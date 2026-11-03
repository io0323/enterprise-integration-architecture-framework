package io.eia.bffgraphql.adapters

// 違反: BFF は自分のデータを持たない(ADR-0043 §8)
import org.jetbrains.exposed.v1.jdbc.Database

class BffUsesExposed(
    val database: Database,
)
