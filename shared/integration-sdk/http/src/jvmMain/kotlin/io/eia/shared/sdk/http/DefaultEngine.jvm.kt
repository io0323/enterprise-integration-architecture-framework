package io.eia.shared.sdk.http

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

public actual fun defaultEngine(): HttpClientEngineFactory<*> = CIO
