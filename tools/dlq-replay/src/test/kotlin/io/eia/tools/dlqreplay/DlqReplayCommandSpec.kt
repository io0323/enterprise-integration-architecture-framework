@file:Suppress("MagicNumber") // テストデータの値・オフセット

package io.eia.tools.dlqreplay

import io.eia.platform.messagingkafka.DeadLetterHeaders
import io.eia.platform.messagingkafka.DeadLetterReplayer
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.Node
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Optional
import kotlin.time.Instant

private const val SOURCE = "payment.payment.cmd-authorize.v1"
private const val DLQ = "$SOURCE.dlq"

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun <E> Result<*, E>.err(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

/** DLQ に 1 件(値は秘密の文字列)を持つ replayer。[sendFails] なら送信が失敗する。 */
private fun fakeReplayer(sendFails: Boolean = false): DeadLetterReplayer {
    val partition = TopicPartition(DLQ, 0)
    val consumer = MockConsumer<ByteArray?, ByteArray?>(AutoOffsetResetStrategy.EARLIEST.name())
    val node = Node(1, "localhost", 9092)
    consumer.updatePartitions(DLQ, listOf(PartitionInfo(DLQ, 0, node, arrayOf(node), arrayOf(node))))
    consumer.updateBeginningOffsets(mapOf(partition to 0L))
    consumer.updateEndOffsets(mapOf(partition to 1L))
    val headers =
        RecordHeaders(
            listOf(
                RecordHeader("ce_id", "0199b6a0-0000-7000-8000-000000000001".toByteArray()),
                RecordHeader(DeadLetterHeaders.REASON, "CARD_EXPIRED".toByteArray()),
                RecordHeader(DeadLetterHeaders.SOURCE_TOPIC, SOURCE.toByteArray()),
            ),
        )
    consumer.schedulePollTask {
        consumer.addRecord(
            ConsumerRecord(
                DLQ,
                0,
                0L,
                0L,
                TimestampType.CREATE_TIME,
                0,
                0,
                "saga-1".toByteArray(),
                "secret-value".toByteArray(),
                headers,
                Optional.empty(),
            ),
        )
    }
    val producer = MockProducer<ByteArray?, ByteArray?>(true, null, ByteArraySerializer(), ByteArraySerializer())
    if (sendFails) producer.sendException = TimeoutException("timeout")
    return DeadLetterReplayer(consumer, producer)
}

private fun run(
    args: List<String>,
    replayer: () -> DeadLetterReplayer = { fakeReplayer() },
): Pair<Int, String> {
    val buffer = ByteArrayOutputStream()
    val code = DlqReplayCommand { replayer() }.run(args, PrintStream(buffer, true, Charsets.UTF_8))
    return code to buffer.toString(Charsets.UTF_8)
}

class DlqReplayCommandSpec :
    FunSpec({
        test("引数: --topic と --limit は必須。既定は dry-run と localhost:19092") {
            val args = DlqReplayArgs.parse(listOf("--topic", DLQ, "--limit", "5")).ok()
            args.request.execute shouldBe false
            args.request.limit shouldBe 5
            args.bootstrapServers shouldBe "localhost:19092"

            DlqReplayArgs.parse(listOf("--limit", "5")).err() shouldContain "--topic"
            DlqReplayArgs.parse(listOf("--topic", DLQ)).err() shouldContain "--limit"
            DlqReplayArgs.parse(listOf("--topic", DLQ, "--limit", "0")).err() shouldContain "1〜1000"
            DlqReplayArgs.parse(listOf("--topic", DLQ, "--limit", "1001")).err() shouldContain "1〜1000"
        }

        test("引数: 条件をすべて読み、誤り(知らない引数・値の欠落・重複・形式・範囲)を拒否する") {
            val args =
                DlqReplayArgs
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
                            "--bootstrap",
                            "kafka:9092",
                        ),
                    ).ok()
            args.request.execute shouldBe true
            args.bootstrapServers shouldBe "kafka:9092"
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
            DlqReplayArgs.parse(base + "--all").err() shouldContain "知らない引数"
            DlqReplayArgs.parse(base + "--reason").err() shouldContain "値がありません"
            DlqReplayArgs.parse(base + listOf("--limit", "2")).err() shouldContain "2 回"
            DlqReplayArgs.parse(base + listOf("--partition", "-1")).err() shouldContain "0 以上"
            DlqReplayArgs.parse(base + listOf("--from-offset", "x")).err() shouldContain "0 以上"
            DlqReplayArgs.parse(base + listOf("--failed-from", "yesterday")).err() shouldContain "ISO 8601"
            DlqReplayArgs.parse(base + listOf("--from-offset", "5", "--to-offset", "4")).err() shouldContain "以下"
            DlqReplayArgs
                .parse(base + listOf("--failed-from", "2026-10-09T02:00:00Z", "--failed-to", "2026-10-09T01:00:00Z"))
                .err() shouldContain "より前"
        }

        test("dry-run は一覧と件数を出して 0 で終わる。値は出さない") {
            val (code, output) = run(listOf("--topic", DLQ, "--limit", "10"))
            code shouldBe DlqReplayCommand.OK
            output shouldContain "dry-run"
            output shouldContain "0\t0\tsaga-1\t0199b6a0-0000-7000-8000-000000000001\t-\tCARD_EXPIRED"
            output shouldContain "PLANNED 1"
            output shouldNotContain "secret-value"
        }

        test("--execute ですべて戻せれば 0、送れないものがあれば 1(もう一度実行してよい)") {
            run(listOf("--topic", DLQ, "--limit", "10", "--execute")).first shouldBe DlqReplayCommand.OK
            val (code, output) = run(listOf("--topic", DLQ, "--limit", "10", "--execute")) { fakeReplayer(sendFails = true) }
            code shouldBe DlqReplayCommand.PARTIAL
            output shouldContain "FAILED 1"
        }

        test("引数の誤り・拒否した DLQ・想定外の例外は 2") {
            run(listOf("--limit", "10")).let { (code, output) ->
                code shouldBe DlqReplayCommand.FAILED
                output shouldContain "使い方"
            }
            run(listOf("--topic", "_cdc.legacy.public.t_juchu.dlq", "--limit", "10")).let { (code, output) ->
                code shouldBe DlqReplayCommand.FAILED
                output shouldContain "cdc-resync.md"
            }
            run(listOf("--topic", DLQ, "--limit", "10")) { error("value=secret") }.let { (code, output) ->
                code shouldBe DlqReplayCommand.FAILED
                output shouldContain "IllegalStateException"
                output shouldNotContain "secret"
            }
        }
    })
