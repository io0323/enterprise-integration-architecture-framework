@file:Suppress("MagicNumber") // テストの待ち時間

package io.eia.order.adapters.out.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class NewTransactionCancellationSpec :
    FunSpec({
        test("処理がスレッドを止めていても、呼び出し元の打ち切りで待たずに戻る(DB に届かないときも予算で応答する。ADR-0024 §3)") {
            val release = CountDownLatch(1)
            val started = TimeSource.Monotonic.markNow()

            val result =
                withTimeoutOrNull(100.milliseconds) {
                    detachedFromCaller {
                        // 止まった JDBC の呼び出しの代わり。修正の前の形なら、これが終わるまで戻らない
                        release.await(5, TimeUnit.SECONDS)
                    }
                }

            result.shouldBeNull()
            (started.elapsedNow() < 2_000.milliseconds) shouldBe true
            release.countDown()
        }

        test("処理の結果と例外は、そのまま呼び出し元に返す") {
            detachedFromCaller { "done" } shouldBe "done"
            shouldThrow<SQLTransientConnectionException> {
                detachedFromCaller<String> { throw SQLTransientConnectionException("test") }
            }
        }
    })
