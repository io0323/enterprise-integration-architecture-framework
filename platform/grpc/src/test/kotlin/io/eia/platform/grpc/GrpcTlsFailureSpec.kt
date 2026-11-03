package io.eia.platform.grpc

import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockRequest
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpc
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpcKt.StockQueryServiceCoroutineStub
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.testsupport.TestPki
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.resilience.CircuitBreakerConfig
import io.eia.shared.resilience.CircuitState
import io.eia.shared.resilience.ResilienceConfig
import io.grpc.Channel
import io.grpc.Grpc
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.TlsChannelCredentials
import io.grpc.TlsServerCredentials
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * 実際の TLS の失敗の判別と、mTLS なしの接続の拒否(ROADMAP P13 の DoD。ADR-0042 §5・§8)。
 * grpc-java を上げて例外の形や alert の文が変わると、ここで失敗する。
 */
class GrpcTlsFailureSpec :
    FunSpec({
        val pki = TestPki()
        val other = TestPki("Other CA")
        val serverCert = pki.issue("server", listOf("localhost"))
        val clientCert = pki.issue("client", listOf("bff-graphql"))
        val observed = TestTelemetry()
        val past = Clock.System.now() - 10.days

        fun server(cert: TestPki.Issued = serverCert) =
            GrpcServer.start(
                GrpcServerConfig(
                    port = 0,
                    tls = cert.tls(pki),
                    allowedClients = setOf("bff-graphql"),
                    verify = verifier(),
                    telemetry = observed.telemetry,
                    services = listOf(GrpcService(TestStockService(), setOf(SCOPE))),
                ),
            )

        val running = server()
        val clientTelemetry = TestTelemetry()
        val client = GrpcClient("inventory", TestTokens(GOOD_TOKEN), clientTelemetry.telemetry, fallbackDeadline = 30.seconds)

        suspend fun call(channel: Channel): Result<*, DomainError> =
            client.call(StockQueryServiceCoroutineStub(channel), StockQueryServiceGrpc.getBatchGetStockMethod()) { metadata ->
                batchGetStock(BatchGetStockRequest.newBuilder().addSkus("SKU-1").build(), metadata)
            }

        suspend fun failure(channel: ManagedChannel): DomainError =
            try {
                val result = call(channel)
                result.shouldBeInstanceOf<Result.Err<DomainError>>()
                result.error
            } finally {
                channel.shutdownNow()
            }

        fun clientAnonymous(port: Int): ManagedChannel =
            Grpc
                .newChannelBuilder("localhost:$port", TlsChannelCredentials.newBuilder().trustManager(pki.caFile.toFile()).build())
                .build()

        afterSpec {
            running.stop()
            pki.close()
            other.close()
        }

        test("許可されたクライアント証明書で、生成したスタブで通信できる") {
            val channel = GrpcChannels.mutualTls("localhost:${running.port}", clientCert.tls(pki))
            val result = call(channel)
            channel.shutdownNow()
            result.shouldBeInstanceOf<Result.Ok<*>>()
        }

        test("平文の接続は拒否する(UNAVAILABLE。証明書のエラーとは判別しない)") {
            val channel = Grpc.newChannelBuilder("localhost:${running.port}", InsecureChannelCredentials.create()).build()
            failure(channel) shouldBe GrpcCallUnavailable("inventory", Status.Code.UNAVAILABLE)
        }

        test("クライアント証明書のない接続は拒否する") {
            failure(clientAnonymous(running.port)) shouldBe GrpcCertificateFailure("inventory", CertificateFailureReason.REJECTED_BY_PEER)
        }

        test("別の CA のクライアント証明書は拒否する") {
            val cert = other.issue("client", listOf("bff-graphql"))
            failure(GrpcChannels.mutualTls("localhost:${running.port}", cert.tls(pki))) shouldBe
                GrpcCertificateFailure("inventory", CertificateFailureReason.REJECTED_BY_PEER)
        }

        test("期限切れのクライアント証明書は拒否し、サーバ側でも理由を数える") {
            // ServerTls は期限切れの証明書を読まないので、チャネルは直接作る
            val expired = pki.issue("client", listOf("bff-graphql"), validFrom = past, validity = 1.days)
            val credentials =
                TlsChannelCredentials
                    .newBuilder()
                    .keyManager(expired.certFile.toFile(), expired.keyFile.toFile())
                    .trustManager(pki.caFile.toFile())
                    .build()
            val channel = Grpc.newChannelBuilder("localhost:${running.port}", credentials).build()

            failure(channel) shouldBe GrpcCertificateFailure("inventory", CertificateFailureReason.EXPIRED)
            // localhost は IPv4 と IPv6 の 2 つに解決され、チャネルが両方を試すので、件数は 1 とは限らない
            observed.counter("eia.grpc.server.certificate_rejections", "reason").getValue("expired") shouldBeGreaterThan 0L
        }

        test("許可の一覧にないクライアント証明書の呼び出しは PERMISSION_DENIED で、サーバ側で数える") {
            val cert = pki.issue("client", listOf("someone-else"))
            failure(GrpcChannels.mutualTls("localhost:${running.port}", cert.tls(pki))) shouldBe
                GrpcCallRejected("inventory", Status.Code.PERMISSION_DENIED)
            observed.counter("eia.grpc.server.certificate_rejections", "reason")["client_not_allowed"] shouldBe 1L
        }

        test("サーバ証明書の CA を信頼できない") {
            val channel = GrpcChannels.mutualTls("localhost:${running.port}", clientCert.tls(other))
            failure(channel) shouldBe GrpcCertificateFailure("inventory", CertificateFailureReason.UNTRUSTED)
        }

        test("サーバ証明書の SAN に接続先の名前がない") {
            val wrong = server(pki.issue("server", listOf("other-host")))
            val channel = GrpcChannels.mutualTls("localhost:${wrong.port}", clientCert.tls(pki))
            failure(channel) shouldBe GrpcCertificateFailure("inventory", CertificateFailureReason.HOSTNAME_MISMATCH)
            wrong.stop()
        }

        test("サーバ証明書の期限が切れている") {
            // ServerTls は期限切れの証明書を読まないので、サーバは直接作る
            val cert = pki.issue("server", listOf("localhost"), validFrom = past, validity = 1.days)
            val credentials =
                TlsServerCredentials
                    .newBuilder()
                    .keyManager(cert.certFile.toFile(), cert.keyFile.toFile())
                    .trustManager(pki.caFile.toFile())
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build()
            val expired =
                Grpc
                    .newServerBuilderForPort(0, credentials)
                    .addService(TestStockService())
                    .build()
                    .start()
            val channel = GrpcChannels.mutualTls("localhost:${expired.port}", clientCert.tls(pki))
            failure(channel) shouldBe GrpcCertificateFailure("inventory", CertificateFailureReason.EXPIRED)
            expired.shutdownNow()
        }

        test("接続できない失敗は一時的な失敗(再試行する)") {
            val channel = GrpcChannels.mutualTls("localhost:1", clientCert.tls(pki))
            failure(channel) shouldBe GrpcCallUnavailable("inventory", Status.Code.UNAVAILABLE)
        }

        test("証明書のエラーは、理由ごとに別のメトリクスに数える") {
            val counted = clientTelemetry.counter("eia.grpc.client.certificate_failures", "reason")
            counted["rejected_by_peer"] shouldBe 2L
            counted["expired"] shouldBe 2L
            counted["untrusted"] shouldBe 1L
            counted["hostname_mismatch"] shouldBe 1L
        }

        context("Resilience との組み合わせ") {
            fun resilient(
                name: String,
                metrics: ResilienceMetrics,
            ) = metrics.resilience(
                name,
                ResilienceConfig(
                    attemptTimeout = 30.seconds,
                    retry = RetryPolicy(maxAttempts = 3),
                    circuitBreaker = CircuitBreakerConfig(minimumCalls = 2, failureRateThreshold = 0.5),
                ),
            )

            test("証明書のエラーは再試行せず、Circuit Breaker を開かない") {
                val telemetry = TestTelemetry()
                val metrics = ResilienceMetrics(telemetry.telemetry.meter)
                val resilience = resilient("certificate", metrics)
                val tokens = TestTokens(GOOD_TOKEN)
                val grpc = GrpcClient("certificate", tokens, telemetry.telemetry)
                val channel = GrpcChannels.mutualTls("localhost:${running.port}", clientCert.tls(other))

                repeat(5) {
                    val result =
                        resilience.execute {
                            grpc.call(StockQueryServiceCoroutineStub(channel), StockQueryServiceGrpc.getBatchGetStockMethod()) { metadata ->
                                batchGetStock(BatchGetStockRequest.getDefaultInstance(), metadata)
                            }
                        }
                    result.shouldBeInstanceOf<Result.Err<DomainError>>().error.shouldBeInstanceOf<GrpcCertificateFailure>()
                }
                channel.shutdownNow()

                tokens.issued shouldBe 5 // 再試行していない
                resilience.circuitBreaker?.state shouldBe CircuitState.CLOSED
            }

            test("接続できない失敗は再試行し、Circuit Breaker を開く") {
                val telemetry = TestTelemetry()
                val resilience = resilient("refused", ResilienceMetrics(telemetry.telemetry.meter))
                val grpc = GrpcClient("refused", TestTokens(GOOD_TOKEN), telemetry.telemetry)
                val channel = GrpcChannels.mutualTls("localhost:1", clientCert.tls(pki))

                repeat(3) {
                    resilience.execute {
                        grpc.call(StockQueryServiceCoroutineStub(channel), StockQueryServiceGrpc.getBatchGetStockMethod()) { metadata ->
                            batchGetStock(BatchGetStockRequest.getDefaultInstance(), metadata)
                        }
                    }
                }
                channel.shutdownNow()

                resilience.circuitBreaker?.state shouldBe CircuitState.OPEN
            }
        }
    })
