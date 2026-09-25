package io.eia.other.domain

// 違反: サービス間のコード依存(連携は契約経由のみ)
import io.eia.sample.domain.DomainDependsOnAdapter

class CrossServiceDependency(
    val foreign: DomainDependsOnAdapter,
)
