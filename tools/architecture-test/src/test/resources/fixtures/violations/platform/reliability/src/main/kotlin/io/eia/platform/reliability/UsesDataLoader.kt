package io.eia.platform.reliability

// 違反: java-dataloader は BFF の adapters と contract-check だけ(ADR-0043 §8)
import org.dataloader.DataLoaderRegistry

class UsesDataLoader(
    val registry: DataLoaderRegistry,
)
