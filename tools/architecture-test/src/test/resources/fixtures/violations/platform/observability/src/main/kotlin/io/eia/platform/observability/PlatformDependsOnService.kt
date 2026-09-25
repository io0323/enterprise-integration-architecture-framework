package io.eia.platform.observability

// 違反: platform → services
import io.eia.sample.domain.DomainDependsOnAdapter

class PlatformDependsOnService(
    val target: DomainDependsOnAdapter,
)
