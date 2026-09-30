@file:OptIn(ExperimentalCoroutinesApi::class)

package io.eia.shared.resilience

import io.eia.shared.kernel.isOk
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.coroutines.testScheduler
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BulkheadSpec :
    FunSpec({
        coroutineTestScope = true

        test("同時実行数の上限までは通し、上限を超えた呼び出しは待たずに BulkheadFull を返す") {
            val listener = RecordingListener()
            val bulkhead = Bulkhead("inventory", BulkheadConfig(maxConcurrentCalls = 2), listener)
            val gate = CompletableDeferred<Unit>()
            var running = 0
            var maxRunning = 0
            coroutineScope {
                val held =
                    List(2) {
                        async {
                            bulkhead.execute {
                                running++
                                maxRunning = maxOf(maxRunning, running)
                                gate.await()
                                running--
                                success()
                            }
                        }
                    }
                testScheduler.runCurrent()
                val started = testScheduler.currentTime
                bulkhead.execute { success() }.errorOrFail() shouldBe BulkheadFull("inventory")
                testScheduler.currentTime shouldBe started
                gate.complete(Unit)
                held.forEach { it.await().isOk shouldBe true }
            }
            maxRunning shouldBe 2
            listener.events shouldBe listOf("rejected:bulkhead_full")
            bulkhead.execute { success() }.isOk shouldBe true
        }

        test("maxWait の間に空けば通し、空かなければ maxWait の後に BulkheadFull を返す") {
            val bulkhead = Bulkhead("inventory", BulkheadConfig(maxConcurrentCalls = 1, maxWait = 500.milliseconds))
            coroutineScope {
                launch {
                    bulkhead.execute {
                        delay(300.milliseconds)
                        success()
                    }
                }
                testScheduler.runCurrent()
                bulkhead.execute { success() }.isOk shouldBe true
                testScheduler.currentTime shouldBe 300
            }
            coroutineScope {
                launch {
                    bulkhead.execute {
                        delay(2.seconds)
                        success()
                    }
                }
                testScheduler.runCurrent()
                val started = testScheduler.currentTime
                bulkhead.execute { success() }.errorOrFail() shouldBe BulkheadFull("inventory")
                (testScheduler.currentTime - started) shouldBe 500
            }
        }

        test("例外でもキャンセルでも枠を返す") {
            val bulkhead = Bulkhead("inventory", BulkheadConfig(maxConcurrentCalls = 1))
            shouldThrow<Boom> { bulkhead.execute<String> { throw Boom() } }
            coroutineScope {
                val job =
                    launch {
                        bulkhead.execute {
                            delay(1.seconds)
                            success()
                        }
                    }
                testScheduler.runCurrent()
                job.cancel()
            }
            bulkhead.execute { success() }.isOk shouldBe true
        }

        test("不正な設定は拒否する") {
            shouldThrow<IllegalArgumentException> { BulkheadConfig(maxConcurrentCalls = 0) }
            shouldThrow<IllegalArgumentException> { BulkheadConfig(maxConcurrentCalls = 1, maxWait = (-1).seconds) }
        }
    })
