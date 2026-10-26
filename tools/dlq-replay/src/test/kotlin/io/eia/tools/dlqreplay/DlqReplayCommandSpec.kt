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

        test("引数: 条件は共通の解析(ReplayCommandLine)で読み、--bootstrap はこのコマンドだけが受け付ける") {
            val args =
                DlqReplayArgs
                    .parse(listOf("--topic", DLQ, "--limit", "10", "--execute", "--reason", "CARD_EXPIRED", "--bootstrap", "kafka:9092"))
                    .ok()
            args.request.execute shouldBe true
            args.request.filter.reason shouldBe "CARD_EXPIRED"
            args.bootstrapServers shouldBe "kafka:9092"
            DlqReplayArgs.parse(listOf("--topic", DLQ, "--limit", "1", "--all")).err() shouldContain "知らない引数"
            DlqReplayArgs.USAGE shouldContain "--exclude-replayed"
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
            run(listOf("--topic", "iot.telemetry.reported.v1.dlq", "--limit", "10")).let { (code, output) ->
                code shouldBe DlqReplayCommand.FAILED
                output shouldContain "reprocess-dlq"
            }
            run(listOf("--topic", DLQ, "--limit", "10")) { error("value=secret") }.let { (code, output) ->
                code shouldBe DlqReplayCommand.FAILED
                output shouldContain "IllegalStateException"
                output shouldNotContain "secret"
            }
        }
    })
