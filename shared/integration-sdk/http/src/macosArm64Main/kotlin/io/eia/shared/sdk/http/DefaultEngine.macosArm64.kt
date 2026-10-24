package io.eia.shared.sdk.http

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

public actual fun defaultEngine(): HttpClientEngineFactory<*> = Darwin
