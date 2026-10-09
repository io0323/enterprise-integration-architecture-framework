@file:Suppress("MagicNumber") // 待ち時間・件数

package io.eia.order.adapters.out.saga

import io.eia.order.adapters.out.persistence.ExposedOrderRepository
import io.eia.order.adapters.out.persistence.ExposedTransactionRunner
import io.eia.order.adapters.out.persistence.OrderDatabaseEnvironment
import io.eia.order.adapters.out.persistence.newTransaction
import io.eia.order.adapters.out.persistence.ok
import io.eia.order.adapters.out.persistence.order
import io.eia.order.domain.Saga
import io.eia.order.domain.SagaFailure
import io.eia.order.domain.SagaState
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.ValidationError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.sql.SQLException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * 注文 Saga の記録を、実際の PostgreSQL で確かめる(ADR-0029 §1・§6)。
 *
 * ご指示 3: 段の期限は **DB の時計** で書いて判定する。このクラス([ExposedSagaStore])は時計を持たないので、アプリの時計がずれていても
 * 判定は変わらない。期限の値が `clock_timestamp()` からの相対で書かれること、判定が `clock_timestamp()` と比べることを、DB の値で確かめる。
 */
class ExposedSagaStoreIT :
    FunSpec({
        val environment = OrderDatabaseEnvironment().also { it.start() }
        afterSpec { environment.close() }

        suspend fun prepared(vararg orderIds: String): Pair<io.eia.order.adapters.out.persistence.OrderDatabase, ExposedSagaStore> {
            val db = environment.newDatabase()
            val repository = ExposedOrderRepository(db.database)
            orderIds.forEach { repository.insert(order(it)).ok() }
            return db to ExposedSagaStore(db.database)
        }

        fun saga(
            n: Int,
            state: SagaState = SagaState.RESERVING_STOCK,
        ) = Saga("saga-$n", order("ord-$n").id, state)

        test("期限は DB の時計 + 段の期限で書く(アプリの時計は使わない)。終端は期限なし") {
            val (db, store) = prepared("ord-1", "ord-2")
            ExposedTransactionRunner(db.database)
                .inTransaction {
                    store.insert(saga(1), 1.hours).flatMapUnit { store.insert(saga(2, SagaState.COMPLETED), null) }
                }.ok()

            // 書いた期限と、DB の時計 + 1 時間の差は、文の実行の間のずれ(1 秒未満)だけ
            db.count(
                "SELECT abs(extract(epoch FROM deadline_at - (clock_timestamp() + interval '1 hour')))::bigint " +
                    "FROM order_saga WHERE saga_id = 'saga-1'",
            ) shouldBe
                0
            db.count("SELECT count(*) FROM order_saga WHERE saga_id = 'saga-2' AND deadline_at IS NULL") shouldBe 1
            // 終端に期限を持たせる・終端でないのに期限がない行は、DB の制約でも入らない
            shouldThrow<SQLException> {
                db.superuser { c ->
                    c.createStatement().use { it.execute("UPDATE order_saga SET deadline_at = NULL WHERE saga_id = 'saga-1'") }
                }
            }.sqlState shouldBe "23514"
        }

        test("ご指示 3: 期限切れの判定は DB の時計と比べる(期限を DB の時計の 1 秒前にすれば選ばれ、1 時間後なら選ばれない)") {
            val (db, store) = prepared("ord-1", "ord-2")
            val transactions = ExposedTransactionRunner(db.database)
            transactions.inTransaction { store.insert(saga(1), 1.hours).flatMapUnit { store.insert(saga(2), 1.hours) } }.ok()
            transactions.inTransaction { store.lockExpired(10) }.ok() shouldBe emptyList()

            db.superuser { c ->
                c.createStatement().use {
                    it.execute("UPDATE order_saga SET deadline_at = clock_timestamp() - interval '1 second' WHERE saga_id = 'saga-2'")
                }
            }
            transactions.inTransaction { store.lockExpired(10) }.ok().map { it.id } shouldBe listOf("saga-2")
        }

        test("短い期限は、DB の時計で過ぎたら選ばれる(期限はマイクロ秒の精度)") {
            val (db, store) = prepared("ord-1")
            val transactions = ExposedTransactionRunner(db.database)
            transactions.inTransaction { store.insert(saga(1), 300.milliseconds) }.ok()
            transactions.inTransaction { store.lockExpired(10) }.ok() shouldBe emptyList()
            Thread.sleep(500)
            transactions.inTransaction { store.lockExpired(10) }.ok().map { it.id } shouldBe listOf("saga-1")
        }

        test("期限切れの取り出しは FOR UPDATE SKIP LOCKED: ほかのトランザクションがロック中の Saga は飛ばす(二重に処理しない)") {
            val (db, store) = prepared("ord-1", "ord-2", "ord-3")
            val transactions = ExposedTransactionRunner(db.database)
            transactions
                .inTransaction {
                    store
                        .insert(
                            saga(1),
                            1.hours,
                        ).flatMapUnit { store.insert(saga(2), 1.hours) }
                        .flatMapUnit { store.insert(saga(3), 1.hours) }
                }.ok()
            db.superuser { c ->
                c.createStatement().use { it.execute("UPDATE order_saga SET deadline_at = clock_timestamp() - interval '1 second'") }
            }

            coroutineScope {
                val firstLocked = CompletableDeferred<List<String>>()
                val release = CompletableDeferred<Unit>()
                val first =
                    async {
                        db.database.newTransaction {
                            firstLocked.complete(store.lockExpired(2).ok().map { it.id })
                            release.await()
                        }
                    }
                val lockedByFirst = firstLocked.await()
                val second = transactions.inTransaction { store.lockExpired(10) }.ok().map { it.id }
                release.complete(Unit)
                first.await()

                lockedByFirst.size shouldBe 2
                (lockedByFirst + second).sorted() shouldBe listOf("saga-1", "saga-2", "saga-3")
            }
        }

        test("ロックして読み、状態・理由・送り直しの回数を書く。注文 1 件に Saga 1 つ") {
            val (db, store) = prepared("ord-1")
            val transactions = ExposedTransactionRunner(db.database)
            transactions.inTransaction { store.insert(saga(1), 1.hours) }.ok()
            val next = saga(1).copy(state = SagaState.VOIDING_PAYMENT, failure = SagaFailure.TIMED_OUT, resends = 2)
            transactions.inTransaction { store.save(next, 1.hours) }.ok()

            transactions.inTransaction { store.findForUpdate("saga-1") }.ok() shouldBe next
            transactions.inTransaction { store.findForUpdate("saga-x") }.ok() shouldBe null
            (
                transactions.inTransaction {
                    store.insert(Saga("saga-dup", order("ord-1").id, SagaState.RESERVING_STOCK), 1.hours)
                } is Result.Err
            ) shouldBe
                true
        }

        test("アプリのロールは Saga の記録を消せない・注文 ID を変えられない(付けた権限だけ)") {
            val (db, store) = prepared("ord-1")
            ExposedTransactionRunner(db.database).inTransaction { store.insert(saga(1), 1.hours) }.ok()
            listOf("DELETE FROM order_saga", "UPDATE order_saga SET order_id = 'x'", "TRUNCATE order_saga").forEach { sql ->
                shouldThrow<SQLException> { db.executeAsApp(sql) }.sqlState shouldBe "42501"
            }
        }

        test("返信の冪等消費の記録(platform/inbox): 同じ ce_id は 2 回目で false。UUID でない ce_id とトランザクションの外は Err") {
            val (db, _) = prepared()
            val transactions = ExposedTransactionRunner(db.database)
            val replies = InboxProcessedReplies(db.database, "order.saga")
            val id = Uuid.random().toString()
            transactions.inTransaction { replies.markProcessed(id, "inventory.stock.reserved.v1") }.ok() shouldBe true
            transactions.inTransaction { replies.markProcessed(id, "inventory.stock.reserved.v1") }.ok() shouldBe false
            db.count("SELECT count(*) FROM inbox.processed_message WHERE consumer_group = 'order.saga'") shouldBe 1

            (
                transactions.inTransaction {
                    replies.markProcessed(
                        "not-a-uuid",
                        "t",
                    )
                } as Result.Err
            ).error.shouldBeInstanceOf<ValidationError>()
            (replies.markProcessed(Uuid.random().toString(), "t") as Result.Err).error.shouldBeInstanceOf<UnexpectedError>()
        }
    })

private inline fun <E> Result<Unit, E>.flatMapUnit(next: () -> Result<Unit, E>): Result<Unit, E> =
    when (this) {
        is Result.Ok -> next()
        is Result.Err -> this
    }
