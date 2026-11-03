package io.eia.platform.grpc

import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockRequest
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockResponse
import io.eia.platform.grpcstubs.inventory.v1.StockLevel
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpc
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpcKt.StockQueryServiceCoroutineStub
import io.eia.platform.observability.context.ObservabilityContext
import io.eia.platform.testsupport.TestPki
import io.eia.shared.kernel.CorrelationId
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.CallDeadline
import io.eia.shared.resilience.withCallDeadline
import io.grpc.ManagedChannel
import io.grpc.Status
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.api.trace.SpanKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 呼び出しの振る舞い: 締め切り・伝搬・認証・状態コード(ADR-0042 §5〜§7)。 */
class GrpcCallsSpec :
    FunSpec({
        val pki = TestPki()
        val serverCert = pki.issue("server", listOf("localhost"))
        val clientCert = pki.issue("client", listOf("bff-graphql"))
        val request = BatchGetStockRequest.newBuilder().addSkus("SKU-1").build()
        val method = StockQueryServiceGrpc.getBatchGetStockMethod()

        class Fixture(
            val server: GrpcServer,
            val channel: ManagedChannel,
            val serverTelemetry: TestTelemetry,
        ) {
            fun close() {
                channel.shutdownNow()
                server.stop(0.seconds)
            }
        }

        fun fixture(
            maxBudget: Duration = 3.seconds,
            scopes: Set<String> = setOf(SCOPE),
            handler: suspend (BatchGetStockRequest) -> BatchGetStockResponse = { BatchGetStockResponse.getDefaultInstance() },
        ): Fixture {
            val telemetry = TestTelemetry()
            val server =
                GrpcServer.start(
                    GrpcServerConfig(
                        port = 0,
                        tls = serverCert.tls(pki),
                        allowedClients = setOf("bff-graphql"),
                        verify = verifier(scopes),
                        telemetry = telemetry.telemetry,
                        services = listOf(GrpcService(TestStockService(handler), setOf(SCOPE))),
                        maxBudget = maxBudget,
                    ),
                )
            return Fixture(server, GrpcChannels.mutualTls("localhost:${server.port}", clientCert.tls(pki)), telemetry)
        }

        suspend fun send(
            fixture: Fixture,
            client: GrpcClient,
        ): Result<BatchGetStockResponse, DomainError> =
            client.call(StockQueryServiceCoroutineStub(fixture.channel), method) { metadata -> batchGetStock(request, metadata) }

        fun client(
            telemetry: TestTelemetry = TestTelemetry(),
            tokens: TestTokens = TestTokens(GOOD_TOKEN),
        ) = GrpcClient("inventory", tokens, telemetry.telemetry, fallbackDeadline = 30.seconds)

        afterSpec { pki.close() }

        context("締め切り") {
            test("呼び出し元の CallDeadline を gRPC の deadline に引き継ぎ、サーバの CallDeadline に届く") {
                val received = CompletableDeferred<Duration>()
                val fixture =
                    fixture(maxBudget = 30.seconds) {
                        received.complete(checkNotNull(CallDeadline.current()).remaining())
                        BatchGetStockResponse.getDefaultInstance()
                    }

                // 負荷の高い環境でも切れない長さにする。サーバの上限(30 秒)ではなく、呼び出し元の値が届くことを確かめる
                withCallDeadline(10.seconds) { send(fixture, client()) }.shouldBeInstanceOf<Result.Ok<*>>()
                fixture.close()

                val remaining = received.await()
                remaining shouldBeLessThan (10.seconds + GrpcClient.DEADLINE_GRACE)
                remaining shouldBeGreaterThan Duration.ZERO
            }

            test("呼び出し元の deadline がサーバの上限より長ければ、サーバの上限で切る") {
                val received = CompletableDeferred<Duration>()
                val fixture =
                    fixture(maxBudget = 200.milliseconds) {
                        received.complete(checkNotNull(CallDeadline.current()).remaining())
                        BatchGetStockResponse.getDefaultInstance()
                    }

                withCallDeadline(30.seconds) { send(fixture, client()) }.shouldBeInstanceOf<Result.Ok<*>>()
                fixture.close()

                received.await() shouldBeLessThan 201.milliseconds
            }

            test("残り時間がなければ送らない") {
                var called = false
                val fixture =
                    fixture {
                        called = true
                        BatchGetStockResponse.getDefaultInstance()
                    }
                val tokens = TestTokens(GOOD_TOKEN)

                val result = withContext(CallDeadline.after(Duration.ZERO)) { send(fixture, client(tokens = tokens)) }
                fixture.close()

                result shouldBe err(GrpcCallUnavailable("inventory", Status.Code.DEADLINE_EXCEEDED))
                called shouldBe false
                tokens.issued shouldBe 0
            }

            test("deadline を超えると、呼び出し側は DEADLINE_EXCEEDED(再試行できる失敗)を受け取り、サーバの処理は取り消される") {
                val cancelled = CompletableDeferred<Unit>()
                val fixture =
                    fixture(maxBudget = 30.seconds) {
                        try {
                            delay(10.seconds)
                        } catch (e: CancellationException) {
                            cancelled.complete(Unit)
                            throw e
                        }
                        BatchGetStockResponse.getDefaultInstance()
                    }

                val result = withContext(CallDeadline.after(100.milliseconds)) { send(fixture, client()) }

                result shouldBe err(GrpcCallUnavailable("inventory", Status.Code.DEADLINE_EXCEEDED))
                withTimeout(2.seconds) { cancelled.await() }
                fixture.close()
            }

            test("grpcResponse は、サーバの上限で打ち切って DEADLINE_EXCEEDED を返す(deadline のない呼び出しでも)") {
                val telemetry = TestTelemetry()
                val server =
                    GrpcServer.start(
                        GrpcServerConfig(
                            port = 0,
                            tls = serverCert.tls(pki),
                            allowedClients = setOf("bff-graphql"),
                            verify = verifier(),
                            telemetry = telemetry.telemetry,
                            services =
                                listOf(
                                    GrpcService(
                                        TestStockService {
                                            grpcResponse<BatchGetStockResponse> {
                                                delay(10.seconds)
                                                ok(BatchGetStockResponse.getDefaultInstance())
                                            }
                                        },
                                        setOf(SCOPE),
                                    ),
                                ),
                            maxBudget = 100.milliseconds,
                        ),
                    )
                val channel = GrpcChannels.mutualTls("localhost:${server.port}", clientCert.tls(pki))

                // fallbackDeadline(30 秒)はサーバの上限より長いので、サーバが先に切る
                val result =
                    client().call(StockQueryServiceCoroutineStub(channel), method) { metadata -> batchGetStock(request, metadata) }
                channel.shutdownNow()
                server.stop(0.seconds)

                result shouldBe err(GrpcCallUnavailable("inventory", Status.Code.DEADLINE_EXCEEDED))
                telemetry.histogramValues("rpc.server.call.duration", "rpc.response.status_code") shouldContain "DEADLINE_EXCEEDED"
            }
        }

        context("伝搬") {
            test("traceparent と Correlation ID を metadata で運び、サーバのコンテキストに届く") {
                val serverContext = CompletableDeferred<ObservabilityContext>()
                val fixture =
                    fixture {
                        serverContext.complete(checkNotNull(currentCoroutineContext()[ObservabilityContext]))
                        BatchGetStockResponse.getDefaultInstance()
                    }
                val clientTelemetry = TestTelemetry()
                val correlationId = CorrelationId.generate()

                withContext(ObservabilityContext(correlationId)) {
                    send(fixture, client(clientTelemetry))
                }.shouldBeInstanceOf<Result.Ok<*>>()
                fixture.close()

                serverContext.await().correlationId shouldBe correlationId
                val clientSpan = clientTelemetry.spans.finishedSpanItems.single()
                val serverSpan =
                    fixture.serverTelemetry.spans.finishedSpanItems
                        .single()
                clientSpan.kind shouldBe SpanKind.CLIENT
                serverSpan.kind shouldBe SpanKind.SERVER
                serverSpan.traceId shouldBe clientSpan.traceId
                serverSpan.parentSpanId shouldBe clientSpan.spanId
            }

            test("Correlation ID がなければ、サーバが採番する") {
                val serverContext = CompletableDeferred<ObservabilityContext>()
                val fixture =
                    fixture {
                        serverContext.complete(checkNotNull(currentCoroutineContext()[ObservabilityContext]))
                        BatchGetStockResponse.getDefaultInstance()
                    }

                send(fixture, client()).shouldBeInstanceOf<Result.Ok<*>>()
                fixture.close()

                serverContext.await().correlationId shouldNotBe null
            }

            test("RED のメトリクスに、状態コードと依存先を記録する(要求の内容は入れない)") {
                val fixture = fixture()
                val clientTelemetry = TestTelemetry()

                send(fixture, client(clientTelemetry)).shouldBeInstanceOf<Result.Ok<*>>()
                fixture.close()

                clientTelemetry.histogramValues("rpc.client.call.duration", "rpc.response.status_code") shouldBe listOf("OK")
                clientTelemetry.histogramValues("rpc.client.call.duration", "eia.dependency.name") shouldBe listOf("inventory")
                fixture.serverTelemetry.histogramValues("rpc.server.call.duration", "rpc.response.status_code") shouldBe listOf("OK")
            }
        }

        context("認証") {
            test("トークンを拒否されたら、捨てて 1 回だけ取り直す") {
                val fixture = fixture()
                val tokens = TestTokens("expired-token", GOOD_TOKEN)

                send(fixture, client(tokens = tokens)).shouldBeInstanceOf<Result.Ok<*>>()
                fixture.close()

                tokens.invalidated shouldBe listOf("expired-token")
                tokens.issued shouldBe 2
            }

            test("取り直しても拒否されたら NonRetryable") {
                val fixture = fixture()
                val tokens = TestTokens("bad-token")

                val result = send(fixture, client(tokens = tokens))
                fixture.close()

                result shouldBe err(GrpcCallRejected("inventory", Status.Code.UNAUTHENTICATED))
                tokens.issued shouldBe 2
            }

            test("スコープが足りなければ PERMISSION_DENIED で、サービスの実装を呼ばない") {
                var called = false
                val fixture =
                    fixture(scopes = setOf("other.scope:read")) {
                        called = true
                        BatchGetStockResponse.getDefaultInstance()
                    }

                val result = send(fixture, client())
                fixture.close()

                result shouldBe err(GrpcCallRejected("inventory", Status.Code.PERMISSION_DENIED))
                called shouldBe false
            }

            test("スコープのないサービスは公開できない") {
                shouldThrow<IllegalArgumentException> { GrpcService(TestStockService(), emptySet()) }
            }
        }

        context("状態コードと応答") {
            test("grpcResponse は Result の値を応答にする") {
                val level =
                    StockLevel
                        .newBuilder()
                        .setSku("SKU-1")
                        .setAvailable(3)
                        .build()
                val fixture =
                    fixture {
                        grpcResponse<BatchGetStockResponse> { ok(BatchGetStockResponse.newBuilder().addLevels(level).build()) }
                    }

                val result = send(fixture, client())
                fixture.close()

                result.shouldBeInstanceOf<Result.Ok<BatchGetStockResponse>>().value.levelsList shouldBe listOf(level)
            }

            test("検証のエラーは INVALID_ARGUMENT(再試行しない)") {
                val fixture =
                    fixture {
                        grpcResponse<BatchGetStockResponse> { err(ValidationError.of("skus", "empty")) }
                    }

                val result = send(fixture, client())
                fixture.close()

                result shouldBe err(GrpcCallRejected("inventory", Status.Code.INVALID_ARGUMENT))
            }

            test("想定外の例外は INTERNAL で、例外の文を呼び出し元に返さない") {
                val fixture =
                    fixture {
                        grpcResponse<BatchGetStockResponse> { error("secret detail") }
                    }

                val result = send(fixture, client())
                fixture.close()

                result shouldBe err(GrpcCallRejected("inventory", Status.Code.INTERNAL))
            }

            test("状態コードの写し方(ADR-0042 §7)") {
                listOf(
                    Status.Code.UNAVAILABLE,
                    Status.Code.DEADLINE_EXCEEDED,
                    Status.Code.RESOURCE_EXHAUSTED,
                    Status.Code.ABORTED,
                ).forEach { Status.fromCode(it).toDomainError("d").shouldBeInstanceOf<DomainError.Retryable>() }
                listOf(
                    Status.Code.INVALID_ARGUMENT,
                    Status.Code.NOT_FOUND,
                    Status.Code.ALREADY_EXISTS,
                    Status.Code.PERMISSION_DENIED,
                    Status.Code.UNAUTHENTICATED,
                    Status.Code.FAILED_PRECONDITION,
                    Status.Code.OUT_OF_RANGE,
                    Status.Code.UNIMPLEMENTED,
                    Status.Code.INTERNAL,
                    Status.Code.DATA_LOSS,
                    Status.Code.UNKNOWN,
                ).forEach { Status.fromCode(it).toDomainError("d").shouldBeInstanceOf<GrpcCallRejected>() }
                Status.CANCELLED.toDomainError("d").shouldBeInstanceOf<GrpcCallCancelled>()
            }
        }
    })
