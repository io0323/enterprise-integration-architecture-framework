@file:Suppress("MagicNumber") // HTTP のステータス・待ち時間

package io.eia.integrationflow.adapters

import com.sun.net.httpserver.HttpServer
import io.eia.integrationflow.adapters.out.saas.CrmCustomersConnector
import io.eia.integrationflow.domain.SourceValue
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.resilience.ResilienceConfig
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * CRM の API の Connector を、実際の HTTP サーバ(ソケット)に対して確かめる(ADR-0035 §6)。
 * #8-c の残り: SaaS の 429 で `Retry-After` の秒数だけ待ってから再試行し、`eia.resilience.retries`(依存先 `saas-crm`)に出ること。
 */
class CrmConnectorIT :
    FunSpec({
        val requests = CopyOnWriteArrayList<String>()
        val rateLimited = AtomicInteger(0)
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/customers") { exchange ->
                    requests += exchange.requestURI.rawQuery.orEmpty()
                    val (status, body) =
                        when {
                            exchange.requestHeaders.getFirst("Authorization") != "Bearer api-token" -> {
                                401 to """{"error":"unauthorized"}"""
                            }

                            rateLimited.getAndDecrement() > 0 -> {
                                429 to """{"error":"rate_limited","message":"Too Many Requests"}"""
                            }

                            "cursor=" !in exchange.requestURI.rawQuery.orEmpty() -> {
                                200 to
                                    """{"data":[{"id":"cus_1","version":1}],"next_cursor":"Y3VzXzE"}"""
                            }

                            else -> {
                                200 to """{"data":[{"id":"cus_2","version":4,"vendor_only":true}],"next_cursor":null}"""
                            }
                        }
                    if (status == 429) exchange.responseHeaders.add("Retry-After", "1")
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }
        afterSpec { server.stop(0) }
        val client = HttpClient(CIO)
        afterSpec { client.close() }

        fun connector(
            reader: InMemoryMetricReader,
            token: String = "api-token",
        ): CrmCustomersConnector {
            val meter =
                SdkMeterProvider
                    .builder()
                    .registerMetricReader(reader)
                    .build()
                    .get("test")
            val config =
                ResilienceConfig(
                    attemptTimeout = 5.seconds,
                    deadline = 30.seconds,
                    retry = RetryPolicy(initialDelay = 100.milliseconds, maxAttempts = 4),
                )
            return CrmCustomersConnector(
                client,
                CrmCustomersConnector.CrmApi(URI("http://127.0.0.1:${server.address.port}"), SecretName("CRM_TOKEN"), pageSize = 1),
                EnvSecretProvider(mapOf("CRM_TOKEN" to token)),
                ResilienceMetrics(meter).resilience(CrmCustomersConnector.RESILIENCE_NAME, config),
            )
        }

        fun retries(reader: InMemoryMetricReader): Long =
            reader
                .collectAllMetrics()
                .filter { it.name == "eia.resilience.retries" }
                .flatMap { it.longSumData.points }
                .filter {
                    it.attributes
                        .asMap()
                        .values
                        .contains("saas-crm")
                }.sumOf { it.value }

        test("#8-c: 429 なら Retry-After(1 秒)だけ待ってから再試行し、eia.resilience.retries(saas-crm)に出る。ページの続きはカーソルで読む") {
            val reader = InMemoryMetricReader.create()
            val crm = connector(reader)
            rateLimited.set(2)
            requests.clear()
            val started = TimeSource.Monotonic.markNow()
            val first = (crm.page("customers", null, null) as? Result.Ok)?.value ?: fail("1 ページ目を読めません")
            (started.elapsedNow() >= 2.seconds) shouldBe true
            retries(reader) shouldBe 2
            requests.size shouldBe 3
            (first.records.single() as SourceValue.Obj).fields["id"] shouldBe SourceValue.Text("cus_1")

            val second = (crm.page("customers", null, first.nextCursor) as? Result.Ok)?.value ?: fail("2 ページ目を読めません")
            second.nextCursor shouldBe null
            (second.records.single() as SourceValue.Obj).fields["vendor_only"] shouldBe SourceValue.Bool(true)
            requests.last() shouldBe "limit=1&cursor=Y3VzXzE"
        }

        test("429 が続いて再試行を尽くしたら Retryable の誤り(同期は Watermark を進めずに次の回にやり直す)。401 はリトライしない") {
            val reader = InMemoryMetricReader.create()
            rateLimited.set(10)
            connector(
                reader,
            ).page(
                "customers",
                null,
                null,
            ).shouldBeInstanceOf<Result.Err<*>>()
                .error
                .shouldBeInstanceOf<io.eia.shared.kernel.DomainError.Retryable>()
            retries(reader) shouldBe 3
            rateLimited.set(0)

            val denied = InMemoryMetricReader.create()
            requests.clear()
            connector(denied, token = "wrong")
                .page("customers", null, null)
                .shouldBeInstanceOf<Result.Err<*>>()
                .error
                .shouldBeInstanceOf<io.eia.shared.kernel.DomainError.NonRetryable>()
            requests.size shouldBe 1
            retries(denied) shouldBe 0
        }
    })
