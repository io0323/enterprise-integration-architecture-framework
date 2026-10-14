package io.eia.platform.batch.sla

import io.eia.platform.batch.BatchJob
import io.eia.platform.batch.BatchStorageUnavailable
import io.eia.platform.batch.DagId
import io.eia.platform.batch.InMemoryBatchStore
import io.eia.platform.batch.PartitionKey
import io.eia.platform.batch.RunKey
import io.eia.platform.batch.RunRecord
import io.eia.platform.batch.RunStatus
import io.eia.platform.batch.RunTrigger
import io.eia.platform.batch.StepName
import io.eia.platform.batch.StepRecord
import io.eia.platform.batch.StepStatus
import io.eia.platform.batch.Telemetry
import io.eia.platform.batch.TestClock
import io.eia.platform.batch.Trigger
import io.eia.platform.batch.dag
import io.eia.platform.batch.err
import io.eia.platform.batch.ok
import io.eia.shared.kernel.ok
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SlaMonitorSpec :
    FunSpec({
        val tokyo = TimeZone.of("Asia/Tokyo")
        val day = PartitionKey("2026-10-13")
        // 2026-10-14 01:00 JST に前段(取り込み。60 分の見込み)が始まり、後段(変換。90 分)は 06:00 JST(21:00Z)までに終える
        val start = Instant.parse("2026-10-13T16:00:00Z")
        val ingest =
            dag("test.ingest") {
                step("load") {
                    expected = 60.minutes
                    action { ok(Unit) }
                }
            }
        val transform =
            dag("test.transform") {
                sla = Sla.dailyAt(LocalTime(6, 0), tokyo)
                step("build") {
                    expected = 90.minutes
                    action { ok(Unit) }
                }
            }
        val jobs = listOf(BatchJob(transform, Trigger.AfterDag(ingest.id)), BatchJob(ingest, Trigger.AfterDag(DagId("test.none"))))

        fun runningIngest(store: InMemoryBatchStore) {
            val key = RunKey(ingest.id, day)
            store.runs[key] = RunRecord(key, RunStatus.RUNNING, 1, RunTrigger.MANUAL, null, start, null)
            store.steps[key] = mutableMapOf(StepName("load") to StepRecord(StepName("load"), StepStatus.RUNNING, 1, start, null))
        }

        test("前段の DAG を待っている後段の実行も予測し、前段が遅れるほど余裕が減って負になる") {
            val telemetry = Telemetry()
            val store = InMemoryBatchStore()
            val clock = TestClock(start + 10.minutes)
            val monitor = SlaMonitor(jobs, store, clock, telemetry.runtime.meter)
            runningIngest(store)

            val onTime = monitor.evaluate().ok().single()

            onTime.run shouldBe RunKey(transform.id, day)
            onTime.waitingFor shouldBe ingest.id
            // 前段は 17:00Z に終わる見込み → 後段は 18:30Z。期限 21:00Z まで 150 分
            onTime.predictedEnd shouldBe Instant.parse("2026-10-13T18:30:00Z")
            onTime.slack shouldBe 150.minutes
            telemetry.gauge("eia.batch.sla.slack") shouldBe mapOf("test.transform" to 9000.0)

            // 前段が 4 時間たっても終わらない(20:00Z): 後段は早くても今から 90 分 → 21:30Z。30 分遅れる見込み
            clock.now = start + 4.hours
            val late = monitor.evaluate().ok().single()

            late.slack shouldBe (-30).minutes
            telemetry.gauge("eia.batch.sla.slack") shouldBe mapOf("test.transform" to -1800.0)
        }

        test("実行中の後段は自分のステップから予測する。期限を過ぎた実行はメトリクスに入れない") {
            val telemetry = Telemetry()
            val store = InMemoryBatchStore()
            val clock = TestClock(start + 2.hours)
            val monitor = SlaMonitor(jobs, store, clock, telemetry.runtime.meter)
            val key = RunKey(transform.id, day)
            store.runs[key] = RunRecord(key, RunStatus.RUNNING, 1, RunTrigger.MANUAL, null, start + 1.hours, null)
            store.steps[key] =
                mutableMapOf(StepName("build") to StepRecord(StepName("build"), StepStatus.RUNNING, 1, start + 1.hours, null))

            val prediction = monitor.evaluate().ok().single()
            prediction.waitingFor shouldBe null
            prediction.predictedEnd shouldBe start + 150.minutes

            clock.now = Instant.parse("2026-10-13T22:00:00Z")
            monitor
                .evaluate()
                .ok()
                .single()
                .slack
                .isNegative() shouldBe true
            telemetry.gauge("eia.batch.sla.slack") shouldBe emptyMap()
        }

        test("後段の実行がすでにあれば、前段を待つ予測は作らない。記録の DB が使えなければ Err") {
            val store = InMemoryBatchStore()
            val monitor = SlaMonitor(jobs, store, TestClock(start))
            runningIngest(store)
            val key = RunKey(transform.id, day)
            store.runs[key] = RunRecord(key, RunStatus.SUCCEEDED, 1, RunTrigger.MANUAL, null, start, start)

            monitor.evaluate().ok().shouldBeEmpty()

            store.failing = BatchStorageUnavailable("SQLSTATE 08006")
            monitor.evaluate().err() shouldBe BatchStorageUnavailable("SQLSTATE 08006")
        }

        test("前段の DAG の循環は作れない") {
            shouldThrow<IllegalArgumentException> {
                SlaMonitor(
                    listOf(BatchJob(transform, Trigger.AfterDag(ingest.id)), BatchJob(ingest, Trigger.AfterDag(transform.id))),
                    InMemoryBatchStore(),
                )
            }
        }
    })
