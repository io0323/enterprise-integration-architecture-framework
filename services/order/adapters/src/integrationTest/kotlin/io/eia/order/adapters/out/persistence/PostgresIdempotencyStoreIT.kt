@file:Suppress("MagicNumber") // テストのリース・保持期間・並行数・状態コード

package io.eia.order.adapters.out.persistence

import io.eia.order.domain.OrderStatus
import io.eia.platform.api.idempotency.ClaimResult
import io.eia.platform.api.idempotency.HttpSnapshot
import io.eia.platform.api.idempotency.IdempotencyConfig
import io.eia.platform.api.idempotency.IdempotencyHandler
import io.eia.platform.api.idempotency.IdempotencyOutcome
import io.eia.platform.api.idempotency.IdempotencyRequest
import io.eia.platform.api.idempotency.IdempotencyScope
import io.eia.platform.api.idempotency.Lease
import io.eia.platform.api.idempotency.RequestFingerprint
import io.eia.platform.api.idempotency.StoredResponse
import io.eia.shared.kernel.IdempotencyKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private fun request(
    body: String = """{"sku":"A"}""",
    key: String = "key-1",
    clientId: String = "client-a",
): IdempotencyRequest =
    IdempotencyRequest(
        IdempotencyScope(clientId, IdempotencyKey.parse(key).ok()),
        RequestFingerprint.of("POST", "/v1/orders", "application/json", body.toByteArray()),
    )

private val RESPONSE =
    StoredResponse(
        201,
        listOf("Content-Type" to "application/json", "Location" to "orders/ord-1", "Location" to "orders/ord-1-dup"),
        byteArrayOf(0x7B, 0x00, 0xFF.toByte(), 0x7D),
    )

private fun ClaimResult.lease(): Lease = shouldBeInstanceOf<ClaimResult.Acquired>().lease

class PostgresIdempotencyStoreIT :
    FunSpec({
        val environment = OrderDatabaseEnvironment()
        beforeSpec { environment.start() }
        afterSpec { environment.close() }

        context("claim") {
            test("同じキーの同時の claim(20 本)は、1 つだけが処理中になり、残りは InProgress") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)

                val results = coroutineScope { (1..20).map { async(Dispatchers.IO) { store.claim(request(), 1.minutes) } }.awaitAll() }

                results.filterIsInstance<ClaimResult.Acquired>() shouldHaveSize 1
                results.filterIsInstance<ClaimResult.InProgress>() shouldHaveSize 19
                db.count("SELECT count(*) FROM idempotency_record") shouldBe 1
            }

            test("処理中の記録には、DB の時刻で測ったリースの残り時間を返す") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)
                store.claim(request(), 1.minutes).lease()

                val remaining = store.claim(request(), 1.minutes).shouldBeInstanceOf<ClaimResult.InProgress>().leaseRemaining
                remaining.inWholeMilliseconds shouldBeLessThanOrEqual 60_000
                remaining.inWholeMilliseconds shouldBeGreaterThan 50_000
                // リースの期限は DB の時刻(clock_timestamp())で設定されている
                val setByDbClock =
                    "SELECT count(*) FROM idempotency_record " +
                        "WHERE lease_expires_at BETWEEN now() + interval '50 seconds' AND now() + interval '61 seconds'"
                db.count(setByDbClock) shouldBe 1
            }

            test("リースが切れたら(DB の時刻)、同じ指紋の要求が新しいトークンで引き継ぐ。遅れた前の所有者の complete は拒否される") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)
                val boundary = ExposedTransactionBoundary(db.database)
                val first = store.claim(request(), 300.milliseconds).lease()
                store.claim(request(), 300.milliseconds).shouldBeInstanceOf<ClaimResult.InProgress>()

                delay(600)
                val second = store.claim(request(), 1.minutes).lease()
                second.token shouldNotBe first.token

                boundary.run { store.complete(first, RESPONSE, 1.hours) } shouldBe false
                boundary.run { store.complete(second, RESPONSE, 1.hours) } shouldBe true
                store.claim(request(), 1.minutes).shouldBeInstanceOf<ClaimResult.Completed>().response shouldBe RESPONSE
            }

            test("保持期限(DB の時刻)を過ぎた完了の記録は、新しい要求として受け付ける") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)
                val boundary = ExposedTransactionBoundary(db.database)
                val lease = store.claim(request(), 1.minutes).lease()
                boundary.run { store.complete(lease, RESPONSE, 300.milliseconds) } shouldBe true
                store.claim(request(), 1.minutes).shouldBeInstanceOf<ClaimResult.Completed>()

                delay(600)
                store.claim(request(), 1.minutes).shouldBeInstanceOf<ClaimResult.Acquired>()
            }

            test("業務のトランザクションの中では claim できない(すぐに確定させて、ほかの要求に処理中を見せるため)") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)
                shouldThrow<IllegalStateException> { ExposedTransactionBoundary(db.database).run { store.claim(request(), 1.minutes) } }
            }
        }

        context("指紋が違えば、状態やリースの有効・期限切れに関係なく 422(KeyReused)") {
            test("処理中でリースが有効な記録") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)
                store.claim(request(), 1.minutes).lease()
                IdempotencyHandler(store).keyReused(db, request(body = """{"sku":"B"}"""))
            }

            test("処理中でリースの切れた記録(引き継がず、409 ではなく 422)") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)
                store.claim(request(), 300.milliseconds).lease()
                delay(600)

                // 保存先は引き継がず、既存の記録の指紋を返す
                val claim = store.claim(request(body = """{"sku":"B"}"""), 1.minutes).shouldBeInstanceOf<ClaimResult.InProgress>()
                claim.fingerprint shouldBe request().fingerprint
                IdempotencyHandler(store).keyReused(db, request(body = """{"sku":"B"}"""))
                db.count("SELECT count(*) FROM idempotency_record WHERE state = 'IN_PROGRESS'") shouldBe 1
            }

            test("完了の記録") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)
                val lease = store.claim(request(), 1.minutes).lease()
                ExposedTransactionBoundary(db.database).run { store.complete(lease, RESPONSE, 1.hours) }
                IdempotencyHandler(store).keyReused(db, request(body = """{"sku":"B"}"""))
            }
        }

        context("complete / release") {
            test("complete は呼び出し元のトランザクションに参加する(取り消せば、記録は処理中のまま)") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)
                val lease = store.claim(request(), 1.minutes).lease()

                shouldThrow<IllegalStateException> {
                    ExposedTransactionBoundary(db.database).run {
                        store.complete(lease, RESPONSE, 1.hours) shouldBe true
                        error("業務の失敗")
                    }
                }
                store.claim(request(), 1.minutes).shouldBeInstanceOf<ClaimResult.InProgress>()
            }

            test("トランザクションの外での complete は、使い方の誤りとして例外にする") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)
                val lease = store.claim(request(), 1.minutes).lease()
                shouldThrow<IllegalStateException> { store.complete(lease, RESPONSE, 1.hours) }
            }

            test("保存した応答(状態コード・ヘッダの順序と重複・任意のバイト列の本文)を、そのまま返す") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)
                val lease = store.claim(request(), 1.minutes).lease()
                ExposedTransactionBoundary(db.database).run { store.complete(lease, RESPONSE, 1.hours) }
                store.claim(request(), 1.minutes).shouldBeInstanceOf<ClaimResult.Completed>().response shouldBe RESPONSE
            }

            test("release はトークンが一致する処理中の記録だけを消す") {
                val db = environment.newDatabase()
                val store = PostgresIdempotencyStore(db.database)
                val lease = store.claim(request(), 1.minutes).lease()
                store.release(lease.copy(token = "other"))
                db.count("SELECT count(*) FROM idempotency_record") shouldBe 1
                store.release(lease)
                db.count("SELECT count(*) FROM idempotency_record") shouldBe 0
            }
        }

        test("purgeExpired: 完了は保持期限を過ぎたもの、処理中はリースの期限に猶予を足した時刻を過ぎたものだけを消す") {
            val db = environment.newDatabase()
            val store = PostgresIdempotencyStore(db.database)
            val boundary = ExposedTransactionBoundary(db.database)
            val expired = store.claim(request(key = "completed-expired"), 1.minutes).lease()
            boundary.run { store.complete(expired, RESPONSE, 100.milliseconds) }
            val valid = store.claim(request(key = "completed-valid"), 1.minutes).lease()
            boundary.run { store.complete(valid, RESPONSE, 1.hours) }
            store.claim(request(key = "in-progress-expired"), 100.milliseconds).lease()
            store.claim(request(key = "in-progress-valid"), 1.hours).lease()
            delay(500)

            // リースは切れているが、猶予(1 時間)の内なので、処理中の記録は消さない(引き継ぎの直前の記録を消さない)
            store.purgeExpired(inProgressGrace = 1.hours) shouldBe 1
            db.keys() shouldBe setOf("completed-valid", "in-progress-expired", "in-progress-valid")

            // 猶予(100 ミリ秒)も過ぎたので消す
            store.purgeExpired(inProgressGrace = 100.milliseconds) shouldBe 1
            db.keys() shouldBe setOf("completed-valid", "in-progress-valid")
        }

        context("IdempotencyHandler と組み合わせる(注文の保存と同じトランザクション)") {
            test("201 は注文と応答を一緒に確定し、再送には保存した応答を返す(注文は 1 件)") {
                val db = environment.newDatabase()
                val handler = IdempotencyHandler(PostgresIdempotencyStore(db.database), IdempotencyConfig())
                val boundary = ExposedTransactionBoundary(db.database)
                val repository = ExposedOrderRepository(db.database)
                val place =
                    suspend {
                        repository.insert(order()).ok()
                        HttpSnapshot(201, listOf("Content-Type" to "application/json", "Location" to "orders/ord-1"), "{}".toByteArray())
                    }

                handler.execute(request(), boundary, place).shouldBeInstanceOf<IdempotencyOutcome.Processed>()
                val replay = handler.execute(request(), boundary, place).shouldBeInstanceOf<IdempotencyOutcome.Replayed>()

                replay.response.header("Location") shouldBe "orders/ord-1"
                db.count("SELECT count(*) FROM orders") shouldBe 1
            }

            test("503 は保存せず、注文の保存も取り消す(同じキーで再試行すると処理する)") {
                val db = environment.newDatabase()
                val handler = IdempotencyHandler(PostgresIdempotencyStore(db.database))
                val boundary = ExposedTransactionBoundary(db.database)
                val repository = ExposedOrderRepository(db.database)

                handler
                    .execute(request(), boundary) {
                        repository.insert(order()).ok()
                        HttpSnapshot(503, emptyList(), ByteArray(0))
                    }.shouldBeInstanceOf<IdempotencyOutcome.Processed>()
                db.count("SELECT count(*) FROM orders") shouldBe 0
                db.count("SELECT count(*) FROM idempotency_record") shouldBe 0

                handler
                    .execute(request(), boundary) {
                        repository.insert(order()).ok()
                        HttpSnapshot(201, emptyList(), ByteArray(0))
                    }.shouldBeInstanceOf<IdempotencyOutcome.Processed>()
                db.count("SELECT count(*) FROM orders") shouldBe 1
            }

            test("遷移の更新(楽観的ロック)も同じトランザクションで確定する") {
                val db = environment.newDatabase()
                val handler = IdempotencyHandler(PostgresIdempotencyStore(db.database))
                val boundary = ExposedTransactionBoundary(db.database)
                val repository = ExposedOrderRepository(db.database)
                repository.insert(order()).ok()

                handler.execute(request(key = "cancel-1"), boundary) {
                    val placed = repository.findById(order().id).ok() ?: error("注文がありません")
                    repository.update(placed.transitionTo(OrderStatus.CANCELLED).ok().order).ok()
                    HttpSnapshot(200, emptyList(), ByteArray(0))
                }
                db.count("SELECT count(*) FROM orders WHERE status = 'CANCELLED' AND version = 1") shouldBe 1
                db.count("SELECT count(*) FROM idempotency_record WHERE state = 'COMPLETED'") shouldBe 1
            }
        }
    })

/** 指紋の違う [request] は処理されず、KeyReused になる。 */
private suspend fun IdempotencyHandler.keyReused(
    db: OrderDatabase,
    request: IdempotencyRequest,
) {
    var processed = false
    execute(request, ExposedTransactionBoundary(db.database)) {
        processed = true
        HttpSnapshot(201, emptyList(), ByteArray(0))
    } shouldBe IdempotencyOutcome.KeyReused
    processed shouldBe false
}

private fun OrderDatabase.keys(): Set<String> =
    superuser { connection ->
        connection.createStatement().use { s ->
            s.executeQuery("SELECT idem_key FROM idempotency_record").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
            }
        }
    }
