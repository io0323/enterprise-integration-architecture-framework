package io.eia.platform.batch

import io.eia.shared.kernel.ok
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DagSpec :
    FunSpec({
        test("ステップは定義した順に並び、依存先は先に定義したステップだけ") {
            val definition =
                dag("batch-etl.order-daily") {
                    val a = step("a") { action { ok(Unit) } }
                    val b =
                        step("b", a) {
                            expected = 3.minutes
                            action { ok(Unit) }
                        }
                    step("c", a, b, a) { action { ok(Unit) } }
                }

            definition.steps.map { it.name.value } shouldContainExactly listOf("a", "b", "c")
            definition.step(StepName("c")).dependsOn shouldContainExactly listOf(StepName("a"), StepName("b"))
            definition.step(StepName("b")).expected shouldBe 3.minutes
            definition.step(StepName("a")).retry.maxAttempts shouldBe 3
        }

        test("定義の誤りは作るときに例外") {
            shouldThrow<IllegalArgumentException> { dag("NoDot") { step("a") { action { ok(Unit) } } } }
            shouldThrow<IllegalArgumentException> { dag("test.empty") {} }.message shouldContain "ステップがありません"
            shouldThrow<IllegalArgumentException> { dag("test.noaction") { step("a") {} } }.message shouldContain "action"
            shouldThrow<IllegalArgumentException> {
                dag("test.dup") {
                    step("a") { action { ok(Unit) } }
                    step("a") { action { ok(Unit) } }
                }
            }.message shouldContain "重複"
            lateinit var foreign: StepRef
            dag("test.other") { foreign = step("x") { action { ok(Unit) } } }
            shouldThrow<IllegalArgumentException> { dag("test.foreign") { step("a", foreign) { action { ok(Unit) } } } }
            shouldThrow<IllegalArgumentException> { dag("test.expected") { step("a") { expected = 0.minutes } } }
            shouldThrow<IllegalArgumentException> { dag("test.timeout") { step("a") { timeout = (-1).minutes } } }
            shouldThrow<IllegalArgumentException> { StepName("Upper") }
            shouldThrow<IllegalArgumentException> { PartitionKey("../etc") }
            shouldThrow<IllegalArgumentException> { CheckpointKey(DagId("test.x"), null, "Bad Name") }
        }

        test("日次の起動: 01:00 JST の前は前日の起動、後は当日の起動。パーティションは起動の前日") {
            val schedule = Schedule.daily(LocalTime(1, 0), TimeZone.of("Asia/Tokyo"))

            // 2026-10-14 00:30 JST
            schedule.latest(Instant.parse("2026-10-13T15:30:00Z")) shouldBe
                Schedule.Occurrence(Instant.parse("2026-10-12T16:00:00Z"), PartitionKey("2026-10-12"))
            // 2026-10-14 01:00 JST ちょうど
            schedule.latest(Instant.parse("2026-10-13T16:00:00Z")) shouldBe
                Schedule.Occurrence(Instant.parse("2026-10-13T16:00:00Z"), PartitionKey("2026-10-13"))
        }

        test("週次の起動: 直近の月曜日 03:00 JST。パーティションは指定した日数前") {
            val schedule = Schedule.weekly(DayOfWeek.MONDAY, LocalTime(3, 0), TimeZone.of("Asia/Tokyo"), partitionDaysBefore = 0)

            // 2026-10-14(水)12:00 JST → 2026-10-12(月)03:00 JST
            schedule.latest(Instant.parse("2026-10-14T03:00:00Z")) shouldBe
                Schedule.Occurrence(Instant.parse("2026-10-11T18:00:00Z"), PartitionKey("2026-10-12"))
            // 2026-10-12(月)02:00 JST → 先週の月曜日
            schedule.latest(Instant.parse("2026-10-11T17:00:00Z")).partition shouldBe PartitionKey("2026-10-05")
            shouldThrow<IllegalArgumentException> { Schedule.daily(LocalTime(1, 0), TimeZone.UTC, partitionDaysBefore = -1) }
        }
    })
