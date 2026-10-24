package io.eia.shared.sdk.http

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * ターゲットごとの既定の Ktor Client のエンジン(ADR-0037 §3)。jvm は CIO、js は Js、macosArm64 は Darwin、linuxX64 は Curl。
 * テストや特別な設定が要るときは、[IntegrationClient] にエンジンを作った `HttpClient` を渡して差し替える。
 */
public expect fun defaultEngine(): HttpClientEngineFactory<*>
