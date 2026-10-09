@file:Suppress("MagicNumber") // テストデータの値・オフセット

package io.eia.platform.messagingkafka

import io.eia.shared.kernel.FixedClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.Node
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.util.Optional
import kotlin.time.Instant

private const val SOURCE = "inventory.stock.cmd-reserve.v1"
private const val DLQ = "$SOURCE.dlq"
private val REPLAYED_AT = Instant.parse("2026-10-09T09:00:00Z")

@Suppress("LongParameterList") // DLQ のレコードの項目ごとの既定値
private fun dead(
    partition: Int,
    offset: Long,
    key: String,
    reason: String = "UNKNOWN_SKU",
    failedAt: String = "2026-10-09T01:00:00Z",
    source: String = SOURCE,
    replayed: Boolean = false,
): ConsumerRecord<ByteArray?, ByteArray?> {
    val headers =
        RecordHeaders(
            listOfNotNull(
                RecordHeader("ce_id", "0199b6a0-0000-7000-8000-0000000000$partition$offset".toByteArray()),
                RecordHeader("ce_type", "inventory.stock.cmd-reserve".toByteArray()),
                RecordHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01".toByteArray()),
                RecordHeader(DeadLetterHeaders.REASON, reason.toByteArray()),
                RecordHeader(DeadLetterHeaders.DETAIL, "sku: 在庫の台帳にない".toByteArray()),
                RecordHeader(DeadLetterHeaders.SOURCE_TOPIC, source.toByteArray()),
                RecordHeader(DeadLetterHeaders.SOURCE_PARTITION, "$partition".toByteArray()),
                RecordHeader(DeadLetterHeaders.SOURCE_OFFSET, "${offset + 100}".toByteArray()),
                RecordHeader(DeadLetterHeaders.ATTEMPTS, "1".toByteArray()),
                RecordHeader(DeadLetterHeaders.FAILED_AT, failedAt.toByteArray()),
                if (replayed) RecordHeader(ReplayHeaders.REPLAYED_AT, "2026-10-08T00:00:00Z".toByteArray()) else null,
            ),
        )
    return ConsumerRecord(
        DLQ,
        partition,
        offset,
        0L,
        TimestampType.CREATE_TIME,
        0,
        0,
        key.toByteArray(),
        byteArrayOf(0, 1, offset.toByte()),
        headers,
        Optional.empty(),
    )
}

class DeadLetterReplayerSpec :
    FunSpec({
        val p0 = TopicPartition(DLQ, 0)
        val p1 = TopicPartition(DLQ, 1)

        /** DLQ に [records] を持つ Consumer。末尾は [end](パーティションごと。既定は最後のレコードの次)。 */
        fun replayer(
            records: List<ConsumerRecord<ByteArray?, ByteArray?>>,
            end: Map<TopicPartition, Long> =
                listOf(p0, p1).associateWith { p ->
                    records.count { it.partition() == p.partition() }.toLong()
                },
            later: List<ConsumerRecord<ByteArray?, ByteArray?>> = emptyList(),
        ): Pair<DeadLetterReplayer, MockProducer<ByteArray?, ByteArray?>> {
            val consumer = MockConsumer<ByteArray?, ByteArray?>(AutoOffsetResetStrategy.EARLIEST.name())
            val node = Node(1, "localhost", 9092)
            consumer.updatePartitions(DLQ, listOf(0, 1).map { PartitionInfo(DLQ, it, node, arrayOf(node), arrayOf(node)) })
            consumer.updateBeginningOffsets(mapOf(p0 to 0L, p1 to 0L))
            consumer.updateEndOffsets(end)
            // 割り当てられたパーティション(条件でパーティションを絞ると一部だけ)のレコードを届ける
            consumer.schedulePollTask {
                (records + later)
                    .filter { TopicPartition(it.topic(), it.partition()) in consumer.assignment() }
                    .forEach(consumer::addRecord)
            }
            val producer = MockProducer<ByteArray?, ByteArray?>(true, null, ByteArraySerializer(), ByteArraySerializer())
            return DeadLetterReplayer(consumer, producer, FixedClock(REPLAYED_AT)) to producer
        }

        val records =
            listOf(
                dead(0, 0, "saga-1"),
                dead(0, 1, "saga-2", reason = "UNDECODABLE"),
                dead(0, 2, "saga-1", failedAt = "2026-10-09T03:00:00Z"),
                dead(1, 0, "saga-3"),
                dead(1, 1, "saga-4", replayed = true),
            )

        test("既定(dry-run)は対象を一覧にするだけで送らない。古い順(パーティションごと)に並べる") {
            val (replayer, producer) = replayer(records)
            val report = replayer.replay(ReplayRequest(DLQ, ReplayFilter(), limit = 100, execute = false)).ok()

            producer.history().size shouldBe 0
            report.executed shouldBe false
            report.scanned shouldBe 5
            report.matched shouldBe 5
            report.entries.map { it.partition to it.offset } shouldBe listOf(0 to 0L, 0 to 1L, 0 to 2L, 1 to 0L, 1 to 1L)
            report.count(ReplayOutcome.PLANNED) shouldBe 5
            report.entries.first().reason shouldBe "UNKNOWN_SKU"
            report.entries.first().sourceOffset shouldBe 100L
            report.entries.last().replayedBefore shouldBe true
        }

        test("条件(原因・キー・時刻・パーティションとオフセット・戻したことがあるか)で絞り、上限で打ち切る") {
            fun matched(
                filter: ReplayFilter,
                limit: Int = 100,
            ): List<Pair<Int, Long>> =
                kotlinx.coroutines.runBlocking {
                    replayer(records).first.replay(ReplayRequest(DLQ, filter, limit, execute = false)).ok().entries.map {
                        it.partition to
                            it.offset
                    }
                }

            matched(ReplayFilter(reason = "UNKNOWN_SKU")) shouldBe listOf(0 to 0L, 0 to 2L, 1 to 0L, 1 to 1L)
            matched(ReplayFilter(key = "saga-1")) shouldBe listOf(0 to 0L, 0 to 2L)
            matched(ReplayFilter(failedFrom = Instant.parse("2026-10-09T02:00:00Z"))) shouldBe listOf(0 to 2L)
            matched(ReplayFilter(failedTo = Instant.parse("2026-10-09T02:00:00Z"), partition = 0)) shouldBe listOf(0 to 0L, 0 to 1L)
            matched(ReplayFilter(partition = 0, fromOffset = 1, toOffset = 1)) shouldBe listOf(0 to 1L)
            matched(ReplayFilter(includeReplayed = false, partition = 1)) shouldBe listOf(1 to 0L)
            matched(ReplayFilter(ceType = "other")) shouldBe emptyList()

            val truncated = replayer(records).first.replay(ReplayRequest(DLQ, ReplayFilter(), limit = 2, execute = false)).ok()
            truncated.entries.size shouldBe 2
            truncated.truncated shouldBe 3
        }

        test("execute では、キー・値・ce_id をそのまま元のトピックに戻し、eiaf.dlq.* を外して eiaf.replay.* を付ける") {
            val (replayer, producer) = replayer(records)
            val report = replayer.replay(ReplayRequest(DLQ, ReplayFilter(key = "saga-1"), limit = 1, execute = true)).ok()

            report.count(ReplayOutcome.REPLAYED) shouldBe 1
            val sent = producer.history().single()
            sent.topic() shouldBe SOURCE
            String(sent.key()!!) shouldBe "saga-1"
            sent.value()!!.toList() shouldBe listOf<Byte>(0, 1, 0)
            val headers = sent.headers().associate { it.key() to String(it.value()) }
            headers.keys.none { it.startsWith("eiaf.dlq.") } shouldBe true
            headers["ce_id"] shouldBe "0199b6a0-0000-7000-8000-000000000000"
            headers["traceparent"] shouldBe "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
            headers[ReplayHeaders.DEAD_LETTER_PARTITION] shouldBe "0"
            headers[ReplayHeaders.DEAD_LETTER_OFFSET] shouldBe "0"
            headers[ReplayHeaders.REASON] shouldBe "UNKNOWN_SKU"
            headers[ReplayHeaders.REPLAYED_AT] shouldBe "2026-10-09T09:00:00Z"
        }

        test("一度戻したものを再び戻すときは、前の eiaf.replay.* を新しいものに置き換える") {
            val (replayer, producer) = replayer(records)
            replayer.replay(ReplayRequest(DLQ, ReplayFilter(key = "saga-4"), limit = 1, execute = true)).ok()

            val headers =
                producer
                    .history()
                    .single()
                    .headers()
                    .filter { it.key() == ReplayHeaders.REPLAYED_AT }
            headers.map { String(it.value()) } shouldBe listOf("2026-10-09T09:00:00Z")
        }

        test("元のトピックの記録が DLQ の名前と合わないものは戻さない") {
            val (replayer, producer) = replayer(listOf(dead(0, 0, "saga-9", source = "other.topic.x.v1")))
            val report = replayer.replay(ReplayRequest(DLQ, ReplayFilter(), limit = 10, execute = true)).ok()

            report.count(ReplayOutcome.SKIPPED_SOURCE_MISMATCH) shouldBe 1
            producer.history().size shouldBe 0
        }

        test("始めた時点の末尾より後に DLQ に入ったものは対象にしない") {
            val (replayer, _) = replayer(records.take(1), end = mapOf(p0 to 1L, p1 to 0L), later = listOf(dead(0, 1, "saga-late")))
            val report = replayer.replay(ReplayRequest(DLQ, ReplayFilter(), limit = 10, execute = false)).ok()

            report.entries.map { it.key } shouldBe listOf("saga-1")
        }

        test("送信に失敗したものは FAILED(もう一度実行してよい)") {
            val consumer = MockConsumer<ByteArray?, ByteArray?>(AutoOffsetResetStrategy.EARLIEST.name())
            val node = Node(1, "localhost", 9092)
            consumer.updatePartitions(DLQ, listOf(PartitionInfo(DLQ, 0, node, arrayOf(node), arrayOf(node))))
            consumer.updateBeginningOffsets(mapOf(p0 to 0L))
            consumer.updateEndOffsets(mapOf(p0 to 1L))
            consumer.schedulePollTask { consumer.addRecord(dead(0, 0, "saga-1")) }
            val producer = MockProducer<ByteArray?, ByteArray?>(true, null, ByteArraySerializer(), ByteArraySerializer())
            producer.sendException =
                org.apache.kafka.common.errors
                    .TimeoutException("timeout")

            DeadLetterReplayer(consumer, producer)
                .replay(ReplayRequest(DLQ, ReplayFilter(), 10, execute = true))
                .ok()
                .count(ReplayOutcome.FAILED) shouldBe 1
        }

        test("DLQ でないトピック・生の CDC の DLQ・存在しない DLQ は拒否する") {
            DeadLetterReplayer.refusal(SOURCE).shouldBeInstanceOf<ReplayError.Refused>()
            DeadLetterReplayer.refusal(".dlq").shouldBeInstanceOf<ReplayError.Refused>()
            DeadLetterReplayer.refusal("_cdc.legacy.public.t_juchu.dlq")!!.message shouldContain "cdc-resync.md"
            DeadLetterReplayer.refusal(DLQ) shouldBe null

            val consumer = MockConsumer<ByteArray?, ByteArray?>(AutoOffsetResetStrategy.EARLIEST.name())
            val producer = MockProducer<ByteArray?, ByteArray?>(true, null, ByteArraySerializer(), ByteArraySerializer())
            DeadLetterReplayer(consumer, producer)
                .replay(ReplayRequest("missing.topic.x.v1.dlq", ReplayFilter(), 10, execute = false))
                .err()
                .shouldBeInstanceOf<ReplayError.Refused>()
        }

        test("依頼と条件の誤りは作るときに拒否する") {
            shouldThrow<IllegalArgumentException> { ReplayRequest(DLQ, ReplayFilter(), 0, execute = false) }
            shouldThrow<IllegalArgumentException> { ReplayRequest(DLQ, ReplayFilter(), ReplayRequest.MAX_LIMIT + 1, execute = false) }
            shouldThrow<IllegalArgumentException> { ReplayFilter(fromOffset = -1) }
            shouldThrow<IllegalArgumentException> { ReplayFilter(fromOffset = 5, toOffset = 4) }
            shouldThrow<IllegalArgumentException> {
                ReplayFilter(failedFrom = Instant.parse("2026-10-09T02:00:00Z"), failedTo = Instant.parse("2026-10-09T01:00:00Z"))
            }
            ReplayRequest(DLQ, ReplayFilter(), 1, execute = false).sourceTopic shouldBe SOURCE
            DeadLetterReplayer.parseInstant("2026-10-09T01:00:00Z") shouldBe Instant.parse("2026-10-09T01:00:00Z")
            DeadLetterReplayer.parseInstant("yesterday") shouldBe null
        }
    })
