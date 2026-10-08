package io.eia.legacyorderacl.adapters.inbound

import io.eia.legacyorderacl.adapters.FakeRegistry
import io.eia.legacyorderacl.adapters.RAW_TOPIC
import io.eia.legacyorderacl.adapters.TestRows
import io.eia.legacyorderacl.adapters.ok
import io.eia.legacyorderacl.adapters.outbound.KafkaLegacyOrderStatePublisher
import io.eia.legacyorderacl.application.usecase.TranslateLegacyOrderChangeService
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.NotLeaderOrFollowerException
import org.apache.kafka.common.serialization.ByteArraySerializer

private val PARTITION = TopicPartition(RAW_TOPIC, 0)

class LegacyChangeConsumerSpec :
    FunSpec({
        val runtime = Observability.init(ObservabilityConfig.of("test-acl-consumer").ok(), TelemetrySinks(), installLogAppender = false)
        val registry = FakeRegistry()

        suspend fun fixture(output: MockProducer<ByteArray, ByteArray>): Pair<MockConsumer<ByteArray?, ByteArray?>, LegacyChangeConsumer> {
            val consumer = MockConsumer<ByteArray?, ByteArray?>(AutoOffsetResetStrategy.EARLIEST.name())
            consumer.assign(listOf(PARTITION))
            consumer.updateBeginningOffsets(mapOf(PARTITION to 0L))
            val publisher =
                KafkaLegacyOrderStatePublisher(EventProducer(output, runtime, KafkaLegacyOrderStatePublisher.SOURCE), registry.book())
            val processor =
                LegacyChangeProcessor(
                    TranslateLegacyOrderChangeService(publisher),
                    registry.writerSchemas(),
                    DeadLetterPublisher(MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())),
                    runtime,
                    AclMetrics(runtime.meter),
                )
            return consumer to LegacyChangeConsumer(consumer, processor)
        }

        fun change(
            offset: Long,
            number: String,
            status: String = "1",
        ) = TestRows.record(offset, number, TestRows.bytes(TestRows.envelope("u", after = TestRows.row(number, status = status))))

        test("届いた順に 1 件ずつ発行し、処理を終えたオフセットだけをコミットする") {
            val output = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())
            val (consumer, loop) = fixture(output)
            listOf(change(0, "J1", "1"), change(1, "J1", "2"), change(2, "J2", "1"), change(3, "J1", "3")).forEach(consumer::addRecord)

            loop.pollOnce().shouldBeNull()

            output.history().map { String(it.key()) } shouldBe listOf("J1", "J1", "J2", "J1")
            consumer.committed(setOf(PARTITION))[PARTITION] shouldBe OffsetAndMetadata(4)
        }

        test("一時的な失敗では、処理を終えた分までをコミットし、残りを未処理の位置から読み直す(送り直しは失敗した 1 件から)") {
            // 2 件目の送信でリーダーの交代を起こす
            val output =
                object : MockProducer<ByteArray, ByteArray>(true, null, ByteArraySerializer(), ByteArraySerializer()) {
                    var sends = 0

                    override fun send(
                        record: org.apache.kafka.clients.producer.ProducerRecord<ByteArray, ByteArray>,
                        callback: org.apache.kafka.clients.producer.Callback?,
                    ): java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> {
                        sends++
                        if (sends == 2) {
                            callback?.onCompletion(null, NotLeaderOrFollowerException("leader moved"))
                            return java.util.concurrent.CompletableFuture
                                .failedFuture(NotLeaderOrFollowerException("leader moved"))
                        }
                        return super.send(record, callback)
                    }
                }
            val (consumer, loop) = fixture(output)
            listOf(change(0, "J1"), change(1, "J2"), change(2, "J3")).forEach(consumer::addRecord)

            loop.pollOnce() shouldBe "publish_failed"
            consumer.committed(setOf(PARTITION))[PARTITION] shouldBe OffsetAndMetadata(1)
            consumer.position(PARTITION) shouldBe 1L

            // 読み直すと、失敗した 1 件目(J2)から順に送る
            consumer.addRecord(change(1, "J2"))
            consumer.addRecord(change(2, "J3"))
            loop.pollOnce().shouldBeNull()
            output.history().map { String(it.key()) } shouldBe listOf("J1", "J2", "J3")
            consumer.committed(setOf(PARTITION))[PARTITION] shouldBe OffsetAndMetadata(3)
        }
    })
