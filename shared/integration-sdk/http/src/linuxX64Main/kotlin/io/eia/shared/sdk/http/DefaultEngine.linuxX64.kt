package io.eia.shared.sdk.http

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.curl.Curl

public actual fun defaultEngine(): HttpClientEngineFactory<*> = Curl
