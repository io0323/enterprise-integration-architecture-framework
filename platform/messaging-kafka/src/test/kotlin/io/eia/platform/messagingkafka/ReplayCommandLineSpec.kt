@file:Suppress("MagicNumber") // テストデータの値・オフセット

package io.eia.platform.messagingkafka

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Instant

private const val DLQ = "payment.payment.cmd-authorize.v1.dlq"

private fun entry(outcome: ReplayOutcome) =
    ReplayEntry(
        partition = 0,
        offset = 3,
        key = "saga-1",
        ceId = "0199b6a0-0000-7000-8000-000000000001",
        ceType = null,
        reason = "CARD_EXPIRED",
        detail = null,
        sourceTopic = null,
        sourceOffset = null,
        failedAt = null,
        replayedBefore = false,
        outcome = outcome,
    )

private fun report(vararg outcomes: ReplayOutcome) =
    ReplayReport(DLQ, "payment.payment.cmd-authorize.v1", executed = true, scanned = 5, matched = 4, entries = outcomes.map(::entry))

class ReplayCommandLineSpec :
    FunSpec({
        test("--topic と --limit は必須。既定は dry-run") {
            val args = ReplayCommandLine.parse(listOf("--topic", DLQ, "--limit", "5")).ok()
            args.request.execute shouldBe false
            args.request.limit shouldBe 5
            args.options shouldBe emptyMap()

            ReplayCommandLine.parse(listOf("--limit", "5")).err() shouldContain "--topic"
            ReplayCommandLine.parse(listOf("--topic", DLQ)).err() shouldContain "--limit"
            ReplayCommandLine.parse(listOf("--topic", DLQ, "--limit", "0")).err() shouldContain "1〜1000"
            ReplayCommandLine.parse(listOf("--topic", DLQ, "--limit", "1001")).err() shouldContain "1〜1000"
        }

        test("条件をすべて読み、誤り(知らない引数・値の欠落・重複・形式・範囲)を拒否する") {
            val args =
                ReplayCommandLine
                    .parse(
                        listOf(
                            "--topic",
                            DLQ,
                            "--limit",
                            "10",
                            "--execute",
                            "--reason",
                            "CARD_EXPIRED",
                            "--type",
                            "payment.payment.cmd-authorize",
                            "--key",
                            "saga-1",
                            "--partition",
                            "2",
                            "--from-offset",
                            "3",
                            "--to-offset",
                            "9",
                            "--failed-from",
                            "2026-10-09T01:00:00Z",
                            "--failed-to",
                            "2026-10-09T02:00:00Z",
                            "--exclude-replayed",
                        ),
                    ).ok()
            args.request.execute shouldBe true
            with(args.request.filter) {
                reason shouldBe "CARD_EXPIRED"
                ceType shouldBe "payment.payment.cmd-authorize"
                key shouldBe "saga-1"
                partition shouldBe 2
                fromOffset shouldBe 3L
                toOffset shouldBe 9L
                failedFrom shouldBe Instant.parse("2026-10-09T01:00:00Z")
                failedTo shouldBe Instant.parse("2026-10-09T02:00:00Z")
                includeReplayed shouldBe false
            }

            val base = listOf("--topic", DLQ, "--limit", "1")
            ReplayCommandLine.parse(base + "--all").err() shouldContain "知らない引数"
            ReplayCommandLine.parse(base + "--reason").err() shouldContain "値がありません"
            ReplayCommandLine.parse(base + listOf("--limit", "2")).err() shouldContain "2 回"
            ReplayCommandLine.parse(base + listOf("--partition", "-1")).err() shouldContain "0 以上"
            ReplayCommandLine.parse(base + listOf("--from-offset", "x")).err() shouldContain "0 以上"
            ReplayCommandLine.parse(base + listOf("--failed-from", "yesterday")).err() shouldContain "ISO 8601"
            ReplayCommandLine.parse(base + listOf("--from-offset", "5", "--to-offset", "4")).err() shouldContain "以下"
            ReplayCommandLine
                .parse(base + listOf("--failed-from", "2026-10-09T02:00:00Z", "--failed-to", "2026-10-09T01:00:00Z"))
                .err() shouldContain "より前"
        }

        test("DLQ が決まっているコマンドは --topic を受け付けない。コマンドごとの引数は extraOptions で受け付けて返す") {
            val fixed = "iot.telemetry.reported.v1.dlq"
            ReplayCommandLine
                .parse(listOf("--limit", "3"), deadLetterTopic = fixed)
                .ok()
                .request.deadLetterTopic shouldBe fixed
            ReplayCommandLine.parse(listOf("--limit", "3", "--topic", DLQ), deadLetterTopic = fixed).err() shouldContain "指定できません"

            ReplayCommandLine
                .parse(listOf("--topic", DLQ, "--limit", "3", "--bootstrap", "kafka:9092"), extraOptions = setOf("--bootstrap"))
                .ok()
                .options shouldBe mapOf("--bootstrap" to "kafka:9092")
            ReplayCommandLine.parse(listOf("--topic", DLQ, "--limit", "3", "--bootstrap", "kafka:9092")).err() shouldContain "知らない引数"
        }

        test("表示は位置・キー・ce_id・原因・結果と件数。上限で除いたものがあれば知らせる") {
            val buffer = ByteArrayOutputStream()
            ReplayCommandLine.print(report(ReplayOutcome.REPLAYED, ReplayOutcome.REJECTED), PrintStream(buffer, true, Charsets.UTF_8))
            val output = buffer.toString(Charsets.UTF_8)

            output shouldContain "DLQ: $DLQ → payment.payment.cmd-authorize.v1(execute)"
            output shouldContain "0\t3\tsaga-1\t0199b6a0-0000-7000-8000-000000000001\t-\tCARD_EXPIRED\t-\tfalse\tREPLAYED"
            output shouldContain "REPLAYED 1 / SKIPPED_SOURCE_MISMATCH 0 / REJECTED 1"
            output shouldContain "上限を超えた分"
            output shouldNotContain "dry-run"
        }

        test("終了コード: 見込みと処理できたものだけなら 0、受け付けなかった・送れなかったものがあれば 1") {
            ReplayCommandLine.exitCode(report(ReplayOutcome.PLANNED, ReplayOutcome.REPLAYED)) shouldBe ReplayCommandLine.OK
            ReplayCommandLine.exitCode(report(ReplayOutcome.REPLAYED, ReplayOutcome.REJECTED)) shouldBe ReplayCommandLine.PARTIAL
            ReplayCommandLine.exitCode(report(ReplayOutcome.FAILED)) shouldBe ReplayCommandLine.PARTIAL
        }
    })
