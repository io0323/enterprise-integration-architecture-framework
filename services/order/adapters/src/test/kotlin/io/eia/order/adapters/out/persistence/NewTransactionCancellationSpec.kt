@file:Suppress("MagicNumber") // テストの待ち時間

package io.eia.order.adapters.out.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CountDownLatch
import javax.sql.DataSource
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class NewTransactionCancellationSpec :
    FunSpec({
        test("接続の取得が止まっていても、呼び出し元の打ち切りで待たずに戻る(DB に届かないときも予算で応答する。ADR-0024 §3)") {
            val release = CountDownLatch(1)
            // 修正の前の形なら、ここで解放するまで戻らない(テストが止まらないよう、一定時間で必ず解放する)
            thread(isDaemon = true) {
                Thread.sleep(5_000)
                release.countDown()
            }
            val stuck =
                mockk<DataSource> {
                    every { connection } answers {
                        release.await()
                        throw SQLTransientConnectionException("test")
                    }
                }
            val started = TimeSource.Monotonic.markNow()

            val result = withTimeoutOrNull(100.milliseconds) { Database.connect(stuck).newTransaction { 1 } }

            result.shouldBeNull()
            (started.elapsedNow() < 2_000.milliseconds) shouldBe true
        }
    })
