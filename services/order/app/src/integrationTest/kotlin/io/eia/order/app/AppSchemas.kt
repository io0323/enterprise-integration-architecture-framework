@file:Suppress("MagicNumber") // HTTP のステータス・待ち時間

package io.eia.order.app

import io.eia.order.adapters.out.outbox.OrderEventSchemas
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.shared.kernel.Result
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Schema Registry に関わる app の統合テストの部品(ADR-0025 §3)。

/** order-service が書くイベントの契約のスキーマを登録する(`make schemas` と同じ)。 */
internal fun AppEnvironment.registerSchemas() {
    HttpClient(CIO).use { http ->
        val client = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), http)
        runBlocking {
            OrderEventSchemas.subjects.forEach { subject ->
                check(client.register(subject) is Result.Ok) { "${subject.topic} のスキーマを登録できません" }
            }
        }
    }
}

/**
 * `/health/ready` が 200 になるまで待つ。serve はスキーマ ID を非同期に解決するため(ADR-0025 §3)、起動の直後は 503 のことがある。
 */
internal fun AppEnvironment.awaitReady(
    server: OrderServer,
    timeout: Duration = 30.seconds,
) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (plain.get("http://127.0.0.1:${server.healthPort}/health/ready").statusCode() != 200) {
        check(deadline.hasNotPassedNow()) { "serve が $timeout 以内に ready になりませんでした" }
        Thread.sleep(200)
    }
}
