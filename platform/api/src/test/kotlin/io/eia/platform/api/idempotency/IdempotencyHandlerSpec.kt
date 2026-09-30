package io.eia.platform.api.idempotency

import io.eia.shared.kernel.IdempotencyKey
import io.eia.shared.kernel.Result
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private fun key(value: String): IdempotencyKey = (IdempotencyKey.parse(value) as Result.Ok).value

private fun request(
    body: String = """{"sku":"A","quantity":1}""",
    clientId: String = "client-a",
    key: String = "key-1",
): IdempotencyRequest =
    IdempotencyRequest(
        IdempotencyScope(clientId, key(key)),
        RequestFingerprint.of("POST", "/v1/orders", "application/json", body.toByteArray()),
    )

private fun created(id: String): HttpSnapshot =
    HttpSnapshot(
        201,
        listOf(
            "Content-Type" to "application/json",
            "Location" to "orders/$id",
            "X-Correlation-Id" to "corr-first",
            "Set-Cookie" to "session=abc",
        ),
        """{"id":"$id"}""".toByteArray(),
    )

/** 業務の更新(注文の登録)を数える処理。 */
private class OrderService(
    private val transaction: FakeTransaction,
) {
    val orders = mutableListOf<String>()
    var calls = 0

    fun place(response: HttpSnapshot = created("ord-${calls + 1}")): HttpSnapshot {
        calls++
        val id = "ord-$calls"
        transaction.write { orders += id }
        return response
    }
}

private class Fixture(
    config: IdempotencyConfig = IdempotencyConfig(),
) {
    val clock = MutableClock()
    val transaction = FakeTransaction()
    val store = FakeIdempotencyStore(transaction, clock)
    val service = OrderService(transaction)
    val handler = IdempotencyHandler(store, config)

    suspend fun execute(
        request: IdempotencyRequest = request(),
        process: suspend () -> HttpSnapshot = { service.place() },
    ): IdempotencyOutcome = handler.execute(request, transaction, process)
}

class IdempotencyHandlerSpec :
    FunSpec({
        context("初回と再送") {
            test("初回は処理して、業務の更新と応答の保存を同じトランザクションで確定する") {
                val f = Fixture()
                val outcome = f.execute().shouldBeInstanceOf<IdempotencyOutcome.Processed>()

                outcome.response.status shouldBe 201
                f.service.orders shouldBe listOf("ord-1")
                f.store.isCompleted(request().scope) shouldBe true
                f.transaction.commits shouldBe 1
            }

            test("同じキー・同じ指紋の再送は、処理せずに保存した応答(許可したヘッダだけ)を返す") {
                val f = Fixture()
                f.execute()
                val replay = f.execute().shouldBeInstanceOf<IdempotencyOutcome.Replayed>()

                replay.response shouldBe
                    StoredResponse(
                        201,
                        listOf("Content-Type" to "application/json", "Location" to "orders/ord-1"),
                        """{"id":"ord-1"}""".toByteArray(),
                    )
                f.service.calls shouldBe 1
                f.service.orders shouldBe listOf("ord-1")
            }

            test("JSON のキーの順序と空白だけが違う再送は、同じ要求とみなす") {
                val f = Fixture()
                f.execute(request("""{"sku":"A","quantity":1}"""))
                f.execute(request("""{ "quantity": 1, "sku": "A" }""")).shouldBeInstanceOf<IdempotencyOutcome.Replayed>()
            }

            test("同じキーで内容の違う要求は KeyReused(処理しない)") {
                val f = Fixture()
                f.execute(request("""{"sku":"A","quantity":1}"""))
                f.execute(request("""{"sku":"A","quantity":2}""")) shouldBe IdempotencyOutcome.KeyReused
                f.service.calls shouldBe 1
            }

            test("キーはクライアントごとに独立する") {
                val f = Fixture()
                f.execute(request(clientId = "client-a")).shouldBeInstanceOf<IdempotencyOutcome.Processed>()
                f.execute(request(clientId = "client-b")).shouldBeInstanceOf<IdempotencyOutcome.Processed>()
                f.service.orders shouldBe listOf("ord-1", "ord-2")
            }

            test("保持期間(24 時間)を過ぎたキーは、新しい要求として処理する。purgeExpired で消える") {
                val f = Fixture()
                f.execute()
                f.clock.advance(24.hours - 1.seconds)
                f.execute().shouldBeInstanceOf<IdempotencyOutcome.Replayed>()
                f.store.purgeExpired(60.seconds) shouldBe 0

                f.clock.advance(1.seconds)
                f.store.purgeExpired(60.seconds) shouldBe 1
                f.execute().shouldBeInstanceOf<IdempotencyOutcome.Processed>()
                f.service.calls shouldBe 2
            }
        }

        context("処理中") {
            test("処理中の同じ要求は InProgress で、リースの残り時間を返す。指紋が違えば KeyReused") {
                val f = Fixture()
                val release = CompletableDeferred<Unit>()
                coroutineScope {
                    val first =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            f.execute {
                                release.await()
                                f.service.place()
                            }
                        }
                    f.clock.advance(10.seconds)
                    f.execute() shouldBe IdempotencyOutcome.InProgress(50.seconds)
                    f.execute(request("""{"sku":"B","quantity":1}""")) shouldBe IdempotencyOutcome.KeyReused

                    release.complete(Unit)
                    first.await().shouldBeInstanceOf<IdempotencyOutcome.Processed>()
                }
                f.service.calls shouldBe 1
            }

            test("処理中のままリースが切れたら(プロセスが落ちた場合など)、同じ要求で引き継いで処理する") {
                val f = Fixture()
                f.store.claim(request(), 60.seconds) // 落ちたプロセスが残した処理中の記録
                f.execute() shouldBe IdempotencyOutcome.InProgress(60.seconds)

                f.clock.advance(60.seconds)
                f.execute().shouldBeInstanceOf<IdempotencyOutcome.Processed>()
                f.service.orders shouldBe listOf("ord-1")
            }

            test("リースが切れても、指紋の違う要求は引き継がない") {
                val f = Fixture()
                f.store.claim(request(), 60.seconds)
                f.clock.advance(61.seconds)
                f.execute(request("""{"sku":"B","quantity":1}""")) shouldBe IdempotencyOutcome.KeyReused
                f.service.calls shouldBe 0
            }

            test("引き継がれた後に前の所有者が戻っても、保存できずに取り消され、引き継いだ側の応答を返す(フェンシング)") {
                val f = Fixture()
                val resume = CompletableDeferred<Unit>()
                coroutineScope {
                    val slow =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            f.execute {
                                resume.await()
                                f.service.place(created("ord-slow"))
                            }
                        }
                    f.clock.advance(61.seconds)
                    f.execute { f.service.place(created("ord-fast")) }.shouldBeInstanceOf<IdempotencyOutcome.Processed>()

                    resume.complete(Unit)
                    val outcome = slow.await().shouldBeInstanceOf<IdempotencyOutcome.Replayed>()
                    outcome.response.header("Location") shouldBe "orders/ord-fast"
                }
                // 業務の更新は、引き継いだ側(1 回目の place)だけが残り、遅れた側(2 回目)は取り消された
                f.service.orders shouldBe listOf("ord-1")
                f.transaction.rollbacks shouldBe 1
            }
        }

        context("保存しない応答と失敗") {
            listOf(500, 502, 503, 504, 429, 408).forEach { status ->
                test("$status は保存せず、業務の更新も取り消す。同じキーで再試行すると処理する") {
                    val f = Fixture()
                    val failed = f.execute { f.service.place(HttpSnapshot(status, emptyList(), ByteArray(0))) }

                    failed.shouldBeInstanceOf<IdempotencyOutcome.Processed>().response.status shouldBe status
                    f.service.orders shouldBe emptyList()
                    f.transaction.rollbacks shouldBe 1
                    f.store.size shouldBe 0

                    f
                        .execute()
                        .shouldBeInstanceOf<IdempotencyOutcome.Processed>()
                        .response.status shouldBe 201
                    f.service.orders shouldBe listOf("ord-2")
                }
            }

            listOf(400, 404, 409, 422).forEach { status ->
                test("$status は保存し、再送にも同じ応答を返す") {
                    val f = Fixture()
                    f.execute { HttpSnapshot(status, listOf("Content-Type" to "application/problem+json"), "{}".toByteArray()) }
                    f
                        .execute()
                        .shouldBeInstanceOf<IdempotencyOutcome.Replayed>()
                        .response.status shouldBe status
                }
            }

            test("例外は業務の更新を取り消し、処理中の記録を消して伝える(同じキーで再試行できる)") {
                val f = Fixture()
                shouldThrow<IllegalStateException> {
                    f.execute {
                        f.service.place()
                        error("DB が落ちた")
                    }
                }
                f.service.orders shouldBe emptyList()
                f.store.size shouldBe 0
                f.execute().shouldBeInstanceOf<IdempotencyOutcome.Processed>()
            }

            test("キャンセルされても、処理中の記録を消す") {
                val f = Fixture()
                val never = CompletableDeferred<Unit>()
                coroutineScope {
                    val job =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            f.execute {
                                never.await()
                                f.service.place()
                            }
                        }
                    yield()
                    job.cancelAndJoin()
                }
                f.store.size shouldBe 0
            }

            test("保存する本文が上限を超えたら、業務の更新ごと取り消して例外にする(保存できない応答で確定させない)") {
                val f = Fixture(IdempotencyConfig(maxStoredBodyBytes = 8))
                shouldThrow<IllegalStateException> { f.execute() }
                f.service.orders shouldBe emptyList()
                f.store.size shouldBe 0
            }
        }
    })
