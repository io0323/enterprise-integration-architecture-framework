package io.eia.sample.domain

// 違反: domain → adapters(逆方向)、domain → application(逆方向)
import io.eia.sample.adapters.SampleRepositoryImpl
import io.eia.sample.application.PlaceSampleUseCase

class DomainDependsOnAdapter(
    val repository: SampleRepositoryImpl,
    val useCase: PlaceSampleUseCase,
)
