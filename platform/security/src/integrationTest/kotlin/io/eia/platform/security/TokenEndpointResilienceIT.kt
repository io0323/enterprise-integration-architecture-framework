package io.eia.platform.security

import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.token.AccessToken
import io.eia.platform.security.token.ClientCredentialsConfig
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.platform.security.token.TokenEndpointUnavailable
import io.eia.platform.security.token.TokenError
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.Jitter
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.resilience.CircuitBreakerConfig
import io.eia.shared.resilience.CircuitState
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.resilience.SlidingWindow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlinx.coroutines.delay
import org.testcontainers.containers.Network
import org.testcontainers.toxiproxy.ToxiproxyContainer
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val KEYCLOAK_ALIAS = "keycloak"

/** ToxiproxyContainer が公開するプロキシ用のポート(8666〜8697)の 1 つ。 */
private const val PROXY_PORT = 8666
private val clientSecret: String = newClientSecret()

/**
 * テストの実行時間を抑えるため、Circuit Breaker の窓・Open の期間・試行の Timeout を短くする。
 * - 窓は直近 4 件、4 件そろったら判定し、失敗率 50% 以上で Open。Open は 2 秒。Half-Open で 1 件試す。
 * - 試行の Timeout は 1 秒(Keycloak のトークンの発行は数十ミリ秒。遅延の toxic は 3 秒)。
 * - リトライは 2 回まで(初回を含めて 3 回)、待ち時間は 50ms から。Jitter なし。
 */
private const val WINDOW_SIZE = 4
private val CIRCUIT_BREAKER =
    CircuitBreakerConfig(
        window = SlidingWindow.Count(WINDOW_SIZE),
        minimumCalls = WINDOW_SIZE,
        openDuration = 2.seconds,
        halfOpenPermits = 1,
    )
private val FAST_RETRY = RetryPolicy(initialDelay = 50.milliseconds, maxAttempts = 3, jitter = Jitter.NONE)
private const val SLOW_RESPONSE_MS = 3_000L

/**
 * トークン取得の Retry・Circuit Breaker・締め切りを、Toxiproxy で遅延と切断を入れた実際の Keycloak で確かめる(ROADMAP P04b の DoD)。
 * 構成: ClientCredentialsTokenProvider → Toxiproxy → Keycloak(同じ Docker のネットワーク。イメージは InfraImages)。
 */
class TokenEndpointResilienceIT :
    FunSpec({
        val network = Network.newNetwork()
        val keycloak = keycloakContainer(clientSecret).withNetwork(network).withNetworkAliases(KEYCLOAK_ALIAS)
        val toxiproxy = ToxiproxyContainer(InfraImages.get("TOXIPROXY_IMAGE")).withNetwork(network)
        lateinit var proxy: Proxy
        lateinit var tokenEndpoint: URI

        beforeSpec {
            keycloak.start()
            toxiproxy.start()
            proxy =
                ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort)
                    .createProxy("keycloak", "0.0.0.0:$PROXY_PORT", "$KEYCLOAK_ALIAS:$KEYCLOAK_HTTP_PORT")
            // バックチャネルは要求を受けた URL から決まる(KC_HOSTNAME_BACKCHANNEL_DYNAMIC)ので、Toxiproxy の URL で取得できる
            tokenEndpoint =
                URI.create("http://${toxiproxy.host}:${toxiproxy.getMappedPort(PROXY_PORT)}/realms/eiaf/protocol/openid-connect/token")
        }
        afterSpec {
            toxiproxy.stop()
            keycloak.stop()
            network.close()
        }
        afterTest {
            // テストごとに障害を外す
            proxy.toxics().all.forEach { it.remove() }
            proxy.enable()
        }

        fun provider(
            http: CountingHttpClient,
            resilience: Resilience,
        ): ClientCredentialsTokenProvider =
            ClientCredentialsTokenProvider(
                ClientCredentialsConfig(tokenEndpoint, CLIENT_ID, SECRET_NAME),
                http.client,
                EnvSecretProvider(mapOf(SECRET_NAME.value to clientSecret)),
                resilience = resilience,
            )

        test("遅延と切断で Circuit Breaker が開き、回復後に Half-Open を経て閉じる(Closed → Open → Half-Open → Closed)") {
            val reader = InMemoryMetricReader.create()
            val metrics =
                ResilienceMetrics(
                    SdkMeterProvider
                        .builder()
                        .registerMetricReader(reader)
                        .build()
                        .get("it"),
                )
            val config =
                ResilienceConfig(
                    attemptTimeout = 1.seconds,
                    deadline = 10.seconds,
                    retry = FAST_RETRY,
                    retryBudget = null,
                    circuitBreaker = CIRCUIT_BREAKER,
                )
            // すべての取得で 1 つの Resilience を使い回す(取得のたびに作ると Circuit Breaker の状態が捨てられる)
            val resilience = metrics.resilience(ClientCredentialsTokenProvider.DEFAULT_RESILIENCE_NAME, config)
            val breaker = checkNotNull(resilience.circuitBreaker)

            fun transitions(): Map<Pair<String, String>, Long> =
                reader
                    .collectAllMetrics()
                    .filter { it.name == "eia.resilience.circuit_breaker.transitions" }
                    .flatMap { it.longSumData.points }
                    .associate { point ->
                        val attributes = point.attributes.asMap().mapKeys { it.key.key }
                        (attributes.getValue("from").toString() to attributes.getValue("to").toString()) to point.value
                    }

            CountingHttpClient().use { http ->
                val provider = provider(http, resilience)

                // Closed: 取得できる
                val first = provider.token().shouldBeInstanceOf<Result.Ok<AccessToken>>().value
                breaker.state shouldBe CircuitState.CLOSED

                // 遅延(3 秒)> 試行の Timeout(1 秒)。1 回の取得で 3 回試してタイムアウトし、窓(成功 1・失敗 3)が埋まって Open になる
                proxy.toxics().latency("slow", ToxicDirection.DOWNSTREAM, SLOW_RESPONSE_MS)
                provider.invalidate(first)
                val sentBeforeLatency = http.sent
                provider.token().error() shouldBe TokenEndpointUnavailable("timeout")
                http.sent - sentBeforeLatency shouldBe FAST_RETRY.maxAttempts
                breaker.state shouldBe CircuitState.OPEN

                // Open の間は Keycloak に要求を送らず、すぐに circuit_open を返す
                val sentWhileOpen = http.sent
                val rejected = provider.token().error().shouldBeInstanceOf<TokenEndpointUnavailable>()
                rejected.reason shouldBe "circuit_open"
                http.sent shouldBe sentWhileOpen

                // 遅延を外しても、Open の期間(2 秒)の間は断る
                proxy.toxics().get("slow").remove()
                provider
                    .token()
                    .error()
                    .shouldBeInstanceOf<TokenEndpointUnavailable>()
                    .reason shouldBe "circuit_open"

                // Open の期間が過ぎた後の最初の取得で Half-Open になり、成功して Closed に戻る
                delay(CIRCUIT_BREAKER.openDuration)
                val recovered = provider.token().shouldBeInstanceOf<Result.Ok<AccessToken>>().value
                breaker.state shouldBe CircuitState.CLOSED

                // 切断(Toxiproxy が接続を受け付けない)でも同じように開く。閉じたときに窓は空になるので、
                // 1 回目の取得の 3 回の失敗では開かず、2 回目の取得の最初の失敗で開く(リトライは見送る)。
                // Half-Open の試行が失敗すれば、Open に戻る
                proxy.disable()
                provider.invalidate(recovered)
                provider.token().error() shouldBe TokenEndpointUnavailable("connection")
                breaker.state shouldBe CircuitState.CLOSED
                provider.token().error() shouldBe TokenEndpointUnavailable("connection")
                breaker.state shouldBe CircuitState.OPEN
                delay(CIRCUIT_BREAKER.openDuration)
                provider.token().error() shouldBe TokenEndpointUnavailable("connection")
                breaker.state shouldBe CircuitState.OPEN

                // 回復: 接続を戻し、Open の期間の後に Half-Open を経て閉じる
                proxy.enable()
                delay(CIRCUIT_BREAKER.openDuration)
                provider.token().shouldBeInstanceOf<Result.Ok<AccessToken>>()
                breaker.state shouldBe CircuitState.CLOSED
            }

            // 遅延で 1 回、切断で 1 回開き、Half-Open の試行は 3 回(成功 2・失敗 1)
            @Suppress("MagicNumber")
            transitions() shouldBe
                mapOf(
                    ("closed" to "open") to 2L,
                    ("open" to "half_open") to 3L,
                    ("half_open" to "closed") to 2L,
                    ("half_open" to "open") to 1L,
                )
        }

        test("締め切り(タイムバジェット)を超えたら、実際の遅延でもリトライせずに終わる") {
            val budget = 1500.milliseconds
            val config =
                ResilienceConfig(
                    attemptTimeout = 5.seconds,
                    deadline = budget,
                    retry = FAST_RETRY,
                    retryBudget = null,
                    circuitBreaker = null,
                )
            val resilience = Resilience(ClientCredentialsTokenProvider.DEFAULT_RESILIENCE_NAME, config)
            proxy.toxics().latency("slow", ToxicDirection.DOWNSTREAM, SLOW_RESPONSE_MS)

            CountingHttpClient().use { http ->
                val started = TimeSource.Monotonic.markNow()
                provider(http, resilience).token().error() shouldBe TokenEndpointUnavailable("deadline_exceeded")
                val elapsed = started.elapsedNow()

                // 試行の Timeout(5 秒)ではなく締め切りで打ち切り、遅延(3 秒)の応答も、リトライも待たない
                http.sent shouldBe 1
                elapsed shouldBeGreaterThanOrEqualTo budget
                elapsed shouldBeLessThan SLOW_RESPONSE_MS.milliseconds
            }
        }
    })

private fun Result<AccessToken, TokenError>.error(): TokenError = shouldBeInstanceOf<Result.Err<TokenError>>().error
