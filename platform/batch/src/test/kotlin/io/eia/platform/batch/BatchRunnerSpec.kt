package io.eia.platform.batch

import io.eia.platform.batch.sla.Sla
import io.eia.shared.kernel.ConflictError
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BatchRunnerSpec :
    FunSpec({
        val telemetry = Telemetry()
        val day = PartitionKey("2026-10-13")

        beforeTest { telemetry.spans.reset() }

        fun runner(
            store: BatchStore,
            clock: TestClock = TestClock(),
        ) = BatchRunner(store, telemetry.runtime, clock, BatchMetrics(telemetry.runtime.meter))

        test("依存の順に実行し、依存のないステップは並べて実行する") {
            val store = InMemoryBatchStore()
            val order = mutableListOf<String>()
            val aStarted = CompletableDeferred<Unit>()
            val bStarted = CompletableDeferred<Unit>()
            val dag =
                dag("test.parallel") {
                    val a =
                        step("a") {
                            retry = FAST_RETRY
                            action {
                                aStarted.complete(Unit)
                                // b も同時に動いていなければ、ここで止まる
                                withTimeout(5.seconds) { bStarted.await() }
                                synchronized(order) { order += "a" }
                                ok(Unit)
                            }
                        }
                    val b =
                        step("b") {
                            action {
                                bStarted.complete(Unit)
                                withTimeout(5.seconds) { aStarted.await() }
                                synchronized(order) { order += "b" }
                                ok(Unit)
                            }
                        }
                    step("c", a, b) {
                        action {
                            synchronized(order) { order += "c" }
                            ok(Unit)
                        }
                    }
                }

            val outcome = runner(store).run(dag, day).ok()

            outcome.shouldBeInstanceOf<RunOutcome.Succeeded>()
            outcome.executed.last() shouldBe StepName("c")
            outcome.skipped.shouldBeEmpty()
            order.last() shouldBe "c"
            store.runs.getValue(RunKey(dag.id, day)).status shouldBe RunStatus.SUCCEEDED
            store.locks.shouldBeEmpty()
        }

        test("途中で kill された実行は、次の実行で成功したステップを飛ばして再開する(重複して実行しない)") {
            val store = InMemoryBatchStore()
            val extracted = AtomicInteger()
            val loaded = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            var hang = true
            val dag =
                dag("test.resume") {
                    val extract =
                        step("extract") {
                            action { ctx ->
                                extracted.incrementAndGet()
                                ctx.saveWatermark("offset", "42")
                            }
                        }
                    step("load", extract) {
                        action {
                            if (hang) {
                                entered.complete(Unit)
                                awaitCancellation()
                            }
                            loaded.incrementAndGet()
                            ok(Unit)
                        }
                    }
                }
            val key = RunKey(dag.id, day)

            coroutineScope {
                val killed = launch(Dispatchers.Default) { runner(store).run(dag, day) }
                withTimeout(5.seconds) { entered.await() }
                killed.cancelAndJoin()
            }
            store.runs.getValue(key).status shouldBe RunStatus.RUNNING
            store.steps
                .getValue(key)
                .getValue(StepName("extract"))
                .status shouldBe StepStatus.SUCCEEDED
            store.steps
                .getValue(key)
                .getValue(StepName("load"))
                .status shouldBe StepStatus.RUNNING
            // ロックは放してある(プロセスが落ちた場合は、DB の接続が切れて外れる)
            store.locks.shouldBeEmpty()

            hang = false
            val outcome = runner(store).run(dag, day).ok() as RunOutcome.Succeeded

            outcome.skipped shouldContainExactly listOf(StepName("extract"))
            outcome.executed shouldContainExactly listOf(StepName("load"))
            extracted.get() shouldBe 1
            loaded.get() shouldBe 1
            store.checkpoints[CheckpointKey(dag.id, null, "offset")] shouldBe "42"
            // 成功した実行をもう一度動かしても何もしない
            runner(store).run(dag, day).ok() shouldBe RunOutcome.AlreadySucceeded(key)
            extracted.get() shouldBe 1
        }

        test("リトライできる失敗・例外・時間切れは、既定の回数までリトライする") {
            val store = InMemoryBatchStore()
            val calls = AtomicInteger()
            val dag =
                dag("test.retry") {
                    step("flaky") {
                        retry = FAST_RETRY
                        timeout = 200.milliseconds
                        action {
                            when (calls.incrementAndGet()) {
                                1 -> err(UnavailableError("db"))
                                2 -> error("boom")
                                else -> ok(Unit)
                            }
                        }
                    }
                    step("slow") {
                        retry = FAST_RETRY.copy(maxAttempts = 2)
                        timeout = 50.milliseconds
                        action {
                            delay(1.seconds)
                            ok(Unit)
                        }
                    }
                }

            val error = runner(store).run(dag, day).err()

            calls.get() shouldBe 3
            error.shouldBeInstanceOf<BatchStepFailed>()
            error.step shouldBe StepName("slow")
            error.attempts shouldBe 2
            error.cause shouldBe StepTimedOut(50.milliseconds)
            val steps = store.steps.getValue(RunKey(dag.id, day))
            steps.getValue(StepName("flaky")).status shouldBe StepStatus.SUCCEEDED
            steps.getValue(StepName("flaky")).attempts shouldBe 3
            store.runs.getValue(RunKey(dag.id, day)).status shouldBe RunStatus.FAILED
            telemetry.counter("eia.batch.step.retries") shouldContainKey "test.retry, flaky, unavailable"
            telemetry.counter("eia.batch.step.retries") shouldContainKey "test.retry, flaky, step_threw"
            telemetry.counter("eia.batch.step.retries") shouldContainKey "test.retry, slow, step_timed_out"
        }

        test("リトライできない失敗は 1 回で止め、後段を始めない。直して再実行すると失敗したステップから再開する") {
            val store = InMemoryBatchStore()
            var broken = true
            val ran = mutableListOf<String>()
            val dag =
                dag("test.fail") {
                    val first =
                        step("first") {
                            action {
                                ran += "first"
                                ok(Unit)
                            }
                        }
                    val second =
                        step("second", first) {
                            action {
                                ran += "second"
                                if (broken) err(ConflictError("bad row")) else ok(Unit)
                            }
                        }
                    step("third", second) {
                        action {
                            ran += "third"
                            ok(Unit)
                        }
                    }
                }

            val error = runner(store).run(dag, day).err() as BatchStepFailed
            error.attempts shouldBe 1
            error.message shouldBe "test.fail/2026-10-13 のステップ second が 1 回失敗しました(conflict)"
            ran shouldContainExactly listOf("first", "second")
            val runSpan = telemetry.spans.finishedSpanItems.single { it.name == "test.fail run" }
            runSpan.status.statusCode shouldBe StatusCode.ERROR

            broken = false
            val outcome = runner(store).run(dag, day).ok() as RunOutcome.Succeeded
            outcome.executed shouldContainExactly listOf(StepName("second"), StepName("third"))
            ran shouldContainExactly listOf("first", "second", "second", "third")
            telemetry.counter("eia.batch.runs") shouldContainKey "test.fail, failed"
        }

        test("やり直し(RESTART)は世代を増やし、全ステップとパーティションの Checkpoint をやり直す。Watermark は残す") {
            val store = InMemoryBatchStore()
            val runs = AtomicInteger()
            val dag =
                dag("test.restart") {
                    step("rebuild") {
                        action { ctx ->
                            runs.incrementAndGet()
                            ctx.checkpoint().ok().shouldBeNull()
                            ctx.saveCheckpoint("row-10").ok()
                            ctx.saveWatermark("offset", "${ctx.generation}")
                        }
                    }
                }
            runner(store).run(dag, day).ok()

            val outcome = runner(store).run(dag, day, mode = RunMode.RESTART).ok() as RunOutcome.Succeeded

            outcome.generation shouldBe 2
            runs.get() shouldBe 2
            store.checkpoints[CheckpointKey(dag.id, day, "rebuild")] shouldBe "row-10"
            store.checkpoints[CheckpointKey(dag.id, null, "offset")] shouldBe "2"
        }

        test("同じ実行をほかが実行中なら BatchRunLocked") {
            val store = InMemoryBatchStore()
            val dag = dag("test.locked") { step("only") { action { ok(Unit) } } }
            store.locks += RunKey(dag.id, day)

            runner(store).run(dag, day).err() shouldBe BatchRunLocked(RunKey(dag.id, day))
        }

        test("記録の DB が使えなければ、ステップを始めずに Err") {
            val store = InMemoryBatchStore()
            val dag = dag("test.storage") { step("only") { action { error("呼ばれない") } } }
            store.failing = BatchStorageUnavailable("SQLSTATE 08006")

            runner(store).run(dag, day).err() shouldBe BatchStorageUnavailable("SQLSTATE 08006")
        }

        test("実行の span の親は起動の traceparent。再開しても最初の起動の traceparent を親にし、ステップの span は実行の span の子") {
            val store = InMemoryBatchStore()
            val upstream = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
            var fail = true
            val dag =
                dag("test.trace") {
                    integrationId = "INT-TEST-001"
                    step("only") {
                        retry = RetryPolicy(maxAttempts = 1)
                        action { if (fail) err(ConflictError("x")) else ok(Unit) }
                    }
                }
            runner(store).run(dag, day, RunTrigger(TriggerKind.FILE, traceparent = upstream, correlationId = "corr-1")).err()
            fail = false
            telemetry.spans.reset()

            // 再開: 渡した起動の情報ではなく、最初の起動の情報を使う
            runner(store).run(dag, day, RunTrigger.MANUAL).ok()

            val spans = telemetry.spans.finishedSpanItems
            val run = spans.single { it.name == "test.trace run" }
            run.traceId shouldBe "0af7651916cd43dd8448eb211c80319c"
            run.parentSpanId shouldBe "b7ad6b7169203331"
            run.kind shouldBe SpanKind.INTERNAL
            run.attributes.asMap().mapKeys { it.key.key }["batch.run.resumed"] shouldBe true
            val step = spans.single { it.name == "test.trace only" }
            step.parentSpanId shouldBe run.spanId
            val record = store.runs.getValue(RunKey(dag.id, day))
            record.trigger.correlationId shouldBe "corr-1"
            record.runTraceparent.shouldNotBeNull() shouldBe "00-${run.traceId}-${run.spanId}-01"
        }

        test("SLA の期限を過ぎて終わった実行を数える") {
            val store = InMemoryBatchStore()
            val clock = TestClock()
            val dag =
                dag("test.deadline") {
                    sla = Sla.dailyAt(LocalTime(6, 0), TimeZone.of("Asia/Tokyo"))
                    step("slow") {
                        action {
                            // 2026-10-14 06:00 JST(= 2026-10-13T21:00Z)を過ぎる
                            clock.advance(6.hours)
                            ok(Unit)
                        }
                    }
                }

            runner(store, clock).run(dag, day).ok()

            telemetry.counter("eia.batch.sla.missed") shouldContainKey "test.deadline"
        }

        test("依存のないステップが失敗しても、実行中のほかのステップは最後まで動かす") {
            val store = InMemoryBatchStore()
            val finished = CompletableDeferred<Unit>()
            val dag =
                dag("test.siblings") {
                    step("fails") {
                        retry = RetryPolicy(maxAttempts = 1)
                        action { err(ConflictError("x")) }
                    }
                    step("finishes") {
                        action {
                            delay(50.milliseconds)
                            finished.complete(Unit)
                            ok(Unit)
                        }
                    }
                }

            coroutineScope { async { runner(store).run(dag, day) }.await() }.err().shouldBeInstanceOf<BatchStepFailed>()

            finished.isCompleted shouldBe true
            store.steps
                .getValue(RunKey(dag.id, day))
                .values
                .map { it.status } shouldContainExactlyInAnyOrder listOf(StepStatus.FAILED, StepStatus.SUCCEEDED)
        }
    })
