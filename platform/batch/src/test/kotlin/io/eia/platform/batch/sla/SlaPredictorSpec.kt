package io.eia.platform.batch.sla

import io.eia.platform.batch.DagDefinition
import io.eia.platform.batch.InMemoryBatchStore
import io.eia.platform.batch.PartitionKey
import io.eia.platform.batch.StepName
import io.eia.platform.batch.StepRecord
import io.eia.platform.batch.StepStatus
import io.eia.platform.batch.dag
import io.eia.platform.batch.ok
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SlaPredictorSpec :
    FunSpec({
        val t0 = Instant.parse("2026-10-13T16:00:00Z")

        // extract(10 分) → transform(20 分) → publish(5 分)。並列の report(30 分)は extract の後
        val pipeline: DagDefinition =
            dag("test.sla") {
                val extract =
                    step("extract") {
                        expected = 10.minutes
                        action { ok(Unit) }
                    }
                val transform =
                    step("transform", extract) {
                        expected = 20.minutes
                        action { ok(Unit) }
                    }
                step("publish", transform) {
                    expected = 5.minutes
                    action { ok(Unit) }
                }
                step("report", extract) {
                    expected = 30.minutes
                    action { ok(Unit) }
                }
            }
        val defaults = StepEstimates(pipeline.steps.associate { it.name to it.expected })

        fun running(
            name: String,
            startedAt: Instant,
        ) = StepRecord(StepName(name), StepStatus.RUNNING, 1, startedAt, null)

        fun succeeded(
            name: String,
            startedAt: Instant,
            finishedAt: Instant,
        ) = StepRecord(StepName(name), StepStatus.SUCCEEDED, 1, startedAt, finishedAt)

        test("始まる前の実行は、依存をたどった最も長い経路(extract → report = 40 分)") {
            SlaPredictor.predictEnd(pipeline, emptyMap(), defaults, t0) shouldBe t0 + 40.minutes
        }

        test("前段のステップが見込みを超えて遅れると、まだ始まっていない後段の開始の待ちが延び、完了の予測が後ろにずれる") {
            val steps = mapOf(StepName("extract") to running("extract", t0))

            // 見込みどおり(5 分後): extract は 10 分で終わる見込み → 完了は t0 + 40 分
            SlaPredictor.predictEnd(pipeline, steps, defaults, t0 + 5.minutes) shouldBe t0 + 40.minutes
            // extract が 25 分たっても終わらない: 後段は早くても今から始まる → 完了は今 + 30 分(report)
            SlaPredictor.predictEnd(pipeline, steps, defaults, t0 + 25.minutes) shouldBe t0 + 55.minutes
            // さらに遅れれば、さらにずれる
            SlaPredictor.predictEnd(pipeline, steps, defaults, t0 + 35.minutes) shouldBe t0 + 65.minutes
        }

        test("前段の DAG の完了の予測(earliestStart)が遅れると、始まる前の後段の完了の予測も同じだけずれる") {
            val onTime = SlaPredictor.predictEnd(pipeline, emptyMap(), defaults, t0, earliestStart = t0 + 60.minutes)
            val late = SlaPredictor.predictEnd(pipeline, emptyMap(), defaults, t0, earliestStart = t0 + 90.minutes)

            onTime shouldBe t0 + 100.minutes
            late shouldBe t0 + 130.minutes
        }

        test("成功したステップは実際の終わり、実行中のステップは開始 + 見込み、失敗したステップは今からやり直す") {
            val steps =
                mapOf(
                    StepName("extract") to succeeded("extract", t0, t0 + 12.minutes),
                    StepName("transform") to running("transform", t0 + 12.minutes),
                    StepName("report") to StepRecord(StepName("report"), StepStatus.FAILED, 3, t0 + 12.minutes, t0 + 20.minutes),
                )

            // transform は t0+32 に終わる見込み → publish は t0+37。report は今(t0+20)からやり直して t0+50
            SlaPredictor.predictEnd(pipeline, steps, defaults, t0 + 20.minutes) shouldBe t0 + 50.minutes
        }

        test("実績が 5 回以上あれば最近 20 回の p90、少なければ定義の見込み") {
            val store = InMemoryBatchStore()
            store.history[pipeline.id to StepName("extract")] = (1..10).map { it.minutes }
            store.history[pipeline.id to StepName("transform")] = (1..4).map { it.minutes }

            val estimates = StepEstimates.of(pipeline, store).ok()

            estimates[StepName("extract")] shouldBe 9.minutes
            estimates[StepName("transform")] shouldBe 20.minutes
            StepEstimates.p90(listOf(3.minutes)) shouldBe 3.minutes
        }

        test("日次の期限: 業務の日の翌日の 06:00 JST。日付でないパーティションは期限なし") {
            val sla = Sla.dailyAt(LocalTime(6, 0), TimeZone.of("Asia/Tokyo"))

            sla.deadline.of(PartitionKey("2026-10-13")) shouldBe Instant.parse("2026-10-13T21:00:00Z")
            sla.deadline.of(PartitionKey("sales_daily_20261013010000_001")).shouldBeNull()
            sla.deadline.of(PartitionKey("2026-13-45")).shouldBeNull()
        }
    })
