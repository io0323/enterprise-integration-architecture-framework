@file:Suppress("MagicNumber") // テストデータの金額・件数・待ち時間、乱数のバイト数

package io.eia.order.adapters.out.persistence

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val FAILED: DomainError = UnavailableError("失敗")

class ExposedTransactionRunnerIT :
    FunSpec({
        val environment = OrderDatabaseEnvironment()
        beforeSpec { environment.start() }
        afterSpec { environment.close() }

        context("トランザクションの外で呼ぶ") {
            test("Ok なら確定する") {
                val db = environment.newDatabase()
                val repository = ExposedOrderRepository(db.database)
                ExposedTransactionRunner(db.database).inTransaction { repository.insert(order()) }.ok()
                db.count("SELECT count(*) FROM orders") shouldBe 1
            }

            test("Err なら取り消す") {
                val db = environment.newDatabase()
                val repository = ExposedOrderRepository(db.database)
                val result: Result<Unit, DomainError> =
                    ExposedTransactionRunner(db.database).inTransaction {
                        repository.insert(order()).ok()
                        err(FAILED)
                    }
                result.error() shouldBe FAILED
                db.count("SELECT count(*) FROM orders") shouldBe 0
            }

            test("例外なら取り消して伝える") {
                val db = environment.newDatabase()
                val repository = ExposedOrderRepository(db.database)
                shouldThrow<IllegalStateException> {
                    ExposedTransactionRunner(db.database).inTransaction<Unit> {
                        repository.insert(order()).ok()
                        error("処理の失敗")
                    }
                }
                db.count("SELECT count(*) FROM orders") shouldBe 0
            }

            test("中断して別のスレッドで再開しても、同じトランザクションに参加する") {
                val db = environment.newDatabase()
                val repository = ExposedOrderRepository(db.database)
                val result: Result<Unit, DomainError> =
                    ExposedTransactionRunner(db.database).inTransaction {
                        repository.insert(order("ord-a")).ok()
                        withContext(Dispatchers.Default) { delay(50) }
                        repository.insert(order("ord-b")).ok()
                        err(FAILED)
                    }
                result.error() shouldBe FAILED
                // 2 件とも同じトランザクションで取り消された
                db.count("SELECT count(*) FROM orders") shouldBe 0
            }
        }

        context("呼び出し元のトランザクションの中で呼ぶ(参加する)") {
            test("内側の Ok の書き込みは、外側が確定するまで見えず、外側と一緒に確定する") {
                val db = environment.newDatabase()
                val repository = ExposedOrderRepository(db.database)
                val runner = ExposedTransactionRunner(db.database)
                runner
                    .inTransaction {
                        runner.inTransaction { repository.insert(order("ord-inner")) }.ok()
                        // 外側がまだ確定していないので、別の接続からは見えない
                        db.count("SELECT count(*) FROM orders") shouldBe 0
                        ok(Unit)
                    }.ok()
                db.count("SELECT count(*) FROM orders") shouldBe 1
            }

            test("外側が取り消せば、内側の Ok の書き込みも消える") {
                val db = environment.newDatabase()
                val repository = ExposedOrderRepository(db.database)
                val runner = ExposedTransactionRunner(db.database)
                val result: Result<Unit, DomainError> =
                    runner.inTransaction {
                        runner.inTransaction { repository.insert(order("ord-inner")) }.ok()
                        err(FAILED)
                    }
                result.error() shouldBe FAILED
                db.count("SELECT count(*) FROM orders") shouldBe 0
            }

            test("内側の Err はセーブポイントまで戻し、外側の書き込みは残る") {
                val db = environment.newDatabase()
                val repository = ExposedOrderRepository(db.database)
                val runner = ExposedTransactionRunner(db.database)
                runner
                    .inTransaction {
                        repository.insert(order("ord-outer")).ok()
                        val inner: Result<Unit, DomainError> =
                            runner.inTransaction {
                                repository.insert(order("ord-inner")).ok()
                                err(FAILED)
                            }
                        inner.error() shouldBe FAILED
                        ok(Unit)
                    }.ok()
                db.count("SELECT count(*) FROM orders WHERE id = 'ord-outer'") shouldBe 1
                db.count("SELECT count(*) FROM orders WHERE id = 'ord-inner'") shouldBe 0
            }

            test("内側の例外もセーブポイントまで戻す(外側が例外を扱って続けられる)") {
                val db = environment.newDatabase()
                val repository = ExposedOrderRepository(db.database)
                val runner = ExposedTransactionRunner(db.database)
                runner
                    .inTransaction {
                        repository.insert(order("ord-outer")).ok()
                        shouldThrow<IllegalStateException> {
                            runner.inTransaction<Unit> {
                                repository.insert(order("ord-inner")).ok()
                                error("内側の失敗")
                            }
                        }
                        ok(Unit)
                    }.ok()
                db.count("SELECT count(*) FROM orders") shouldBe 1
            }

            test("制約違反(同じ ID)で内側が Err になっても、外側のトランザクションは使い続けられる") {
                val db = environment.newDatabase()
                val repository = ExposedOrderRepository(db.database)
                val runner = ExposedTransactionRunner(db.database)
                runner
                    .inTransaction {
                        repository.insert(order("ord-1")).ok()
                        runner.inTransaction { repository.insert(order("ord-1")) }.error()
                        // PostgreSQL は、エラーの後はセーブポイントまで戻さないと次の文を受け付けない
                        repository.insert(order("ord-2"))
                    }.ok()
                db.count("SELECT count(*) FROM orders") shouldBe 2
            }
        }
    })
