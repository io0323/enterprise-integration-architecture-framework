package io.eia.shared.sdk.http

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js

public actual fun defaultEngine(): HttpClientEngineFactory<*> = Js
