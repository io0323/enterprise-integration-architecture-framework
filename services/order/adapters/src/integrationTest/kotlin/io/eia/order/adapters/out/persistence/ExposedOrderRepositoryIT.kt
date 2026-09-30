@file:Suppress("MagicNumber") // テストデータの金額・件数・待ち時間、乱数のバイト数

package io.eia.order.adapters.out.persistence

import io.eia.order.application.port.outbound.OrderVersionConflict
import io.eia.order.domain.Order
import io.eia.order.domain.OrderId
import io.eia.order.domain.OrderStatus
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.NotFoundError
import io.eia.shared.kernel.UnexpectedError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class ExposedOrderRepositoryIT :
    FunSpec({
        val environment = OrderDatabaseEnvironment()
        beforeSpec { environment.start() }
        afterSpec { environment.close() }

        test("保存した注文(金額・通貨・配送先・明細・状態・版)を、そのまま読み戻す") {
            val db = environment.newDatabase()
            val repository = ExposedOrderRepository(db.database)
            val placed = order()

            repository.insert(placed).ok()
            val read = repository.findById(placed.id).ok()

            read?.shouldBeEqual(placed)
            read?.orderedAt shouldBe ORDERED_AT
            read?.lines?.map { it.lineAmount.minorUnits } shouldBe listOf(3_150L, 99L)
            repository.findById(OrderId.parse("ord-none").ok()).ok() shouldBe null
        }

        test("同じ ID の注文は ConflictError(明細も書かない)") {
            val db = environment.newDatabase()
            val repository = ExposedOrderRepository(db.database)
            repository.insert(order()).ok()

            repository.insert(order()).error().shouldBeInstanceOf<ConflictError>()
            db.count("SELECT count(*) FROM order_lines") shouldBe 2
        }

        test("トランザクションの外で呼んで途中の文(明細)が失敗したら、先に書いた注文も取り消す") {
            val db = environment.newDatabase()
            db.superuser { it.createStatement().use { s -> s.execute("REVOKE INSERT ON order_lines FROM ${OrderSchema.APP_ROLE}") } }
            val repository = ExposedOrderRepository(db.database)

            repository.insert(order()).error().shouldBeInstanceOf<UnexpectedError>()
            db.count("SELECT count(*) FROM orders") shouldBe 0
        }

        test("更新は状態だけを変え、版を 1 増やす。ない注文は NotFoundError") {
            val db = environment.newDatabase()
            val repository = ExposedOrderRepository(db.database)
            val placed = order()
            repository.insert(placed).ok()

            val confirmed = repository.update(placed.transitionTo(OrderStatus.CONFIRMED).ok().order).ok()
            confirmed.version shouldBe 1
            repository.findById(placed.id).ok()?.status shouldBe OrderStatus.CONFIRMED
            repository.update(order(id = "ord-missing")).error() shouldBeEqual NotFoundError("order", "ord-missing")
        }

        test(
            "並行の遷移: 取消が先に確定 → 行ロックを待っていた確定は OrderVersionConflict → 読み直すと遷移表で拒否 → 遅れた取消は NoOp",
        ) {
            val db = environment.newDatabase()
            val repository = ExposedOrderRepository(db.database)
            val transactions = ExposedTransactionRunner(db.database)
            val placed = order()
            repository.insert(placed).ok()
            // 確定と取消は、どちらも版 0 の同じ注文を読んでいる
            val snapshot = repository.findById(placed.id).ok() ?: error("注文がありません")

            val commitCancel = CompletableDeferred<Unit>()
            coroutineScope {
                // 取消: 更新して行ロックを持ったまま、確定が待ちに入るまで確定させない
                val cancel =
                    async(Dispatchers.IO) {
                        transactions.inTransaction {
                            val updated = repository.update(snapshot.transitionTo(OrderStatus.CANCELLED).ok().order)
                            commitCancel.await()
                            updated
                        }
                    }
                waitUntil { db.count("SELECT count(*) FROM orders WHERE version = 0") == 1L && db.lockWaiters() == 0L }
                // 確定: 別のトランザクションで同じ行を更新する(取消の行ロックを待つ)
                val confirm =
                    async(Dispatchers.IO) {
                        transactions.inTransaction { repository.update(snapshot.transitionTo(OrderStatus.CONFIRMED).ok().order) }
                    }
                waitUntil { db.lockWaiters() == 1L }
                commitCancel.complete(Unit)

                cancel.await().ok().version shouldBe 1
                confirm.await().error() shouldBeEqual OrderVersionConflict(placed.id, 0)
            }

            // 確定の側は最新の状態を読み直し、遷移表で判定し直す
            val latest = repository.findById(placed.id).ok() ?: error("注文がありません")
            latest.status shouldBe OrderStatus.CANCELLED
            latest.version shouldBe 1
            latest.transitionTo(OrderStatus.CONFIRMED).error().shouldBeInstanceOf<ConflictError>()
            // 遅れて届いた同じ取消は NoOp: 保存せず、版も変わらない(イベントも発行しない)
            latest.transitionTo(OrderStatus.CANCELLED).ok().shouldBeInstanceOf<Order.Transition.NoOp>()
            repository.findById(placed.id).ok()?.version shouldBe 1
        }
    })

/** 行ロックの解放を待っているバックエンドの数。 */
private fun OrderDatabase.lockWaiters(): Long =
    count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'")

private suspend fun waitUntil(condition: () -> Boolean) {
    withTimeout(30.seconds) {
        withContext(Dispatchers.IO) {
            while (!condition()) delay(20)
        }
    }
}
