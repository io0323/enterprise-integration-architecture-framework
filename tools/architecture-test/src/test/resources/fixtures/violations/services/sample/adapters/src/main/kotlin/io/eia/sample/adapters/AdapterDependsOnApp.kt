package io.eia.sample.adapters

// 違反: adapters → app(逆方向)
import io.eia.sample.app.SampleConfig

class AdapterDependsOnApp(
    val config: SampleConfig,
)
