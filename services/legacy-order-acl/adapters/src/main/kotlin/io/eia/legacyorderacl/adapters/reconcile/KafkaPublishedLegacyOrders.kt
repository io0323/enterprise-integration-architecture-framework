package io.eia.legacyorderacl.adapters.reconcile

import io.eia.legacyorderacl.adapters.inbound.LegacyChangeHandler
import io.eia.legacyorderacl.adapters.outbound.LegacyOrderChangedV1
import io.eia.legacyorderacl.adapters.outbound.LegacyOrderEventSchemas
import io.eia.legacyorderacl.adapters.outbound.toDomain
import io.eia.legacyorderacl.application.port.outbound.PublishedLegacyOrders
import io.eia.legacyorderacl.application.port.outbound.ReconcileScope
import io.eia.legacyorderacl.domain.LegacyOrder
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * 整形済みのトピックの最新の状態を、ACL が追いついてから読む(ADR-0027)。
 *
 * 1. 生の CDC のトピックの、今の末尾のオフセット E を取る(呼び出し元は、CDC がレガシーを読んだ位置より先まで取り込んだことを確かめ済み)。
 * 2. ACL の Consumer Group のコミット済みのオフセットが E に届くのを待つ(コミット = 発行の完了。ADR-0026 §6)。
 * 3. 出力のトピックの末尾 O までを最初から読み、キーごとに最後の値を残す(compacted。tombstone は削除)。
 *
 * @param consumers 出力を読む Consumer を作る(Consumer Group に参加しない。assign で読む)。呼ぶたびに作って閉じる
 */
public class KafkaPublishedLegacyOrders(
    private val admin: Admin,
    private val consumers: () -> Consumer<ByteArray?, ByteArray?>,
    writerSchemas: WriterSchemas,
    private val group: String = LegacyChangeHandler.GROUP_ID,
    private val waits: ReconcileWaits = ReconcileWaits(),
) : PublishedLegacyOrders {
    private val rawTopic: String = LegacyChangeHandler.TOPIC
    private val outputTopic: String = LegacyOrderEventSchemas.LEGACY_ORDER_CHANGED.name
    private val deserializer = AvroEventDeserializer(LegacyOrderChangedV1.serializer(), writerSchemas)

    override suspend fun readCaughtUp(scope: ReconcileScope): Result<Map<String, LegacyOrder>, DomainError> =
        try {
            when (val caughtUp = awaitAclCaughtUp()) {
                is Result.Err -> caughtUp
                is Result.Ok -> readLatest(scope)
            }
        } catch (e: ExecutionException) {
            err(UnavailableError("Kafka に問い合わせられません(${e.cause?.let { it::class.simpleName }})"))
        } catch (e: KafkaException) {
            err(UnavailableError("Kafka に問い合わせられません(${e::class.simpleName})"))
        } catch (e: IllegalStateException) {
            err(UnavailableError(e.message ?: "出力のトピックを読めません"))
        }

    private suspend fun awaitAclCaughtUp(): Result<Unit, DomainError> {
        val ends = endOffsets(rawTopic)
        val deadline = TimeSource.Monotonic.markNow() + waits.timeout
        while (true) {
            val committed =
                withContext(Dispatchers.IO) {
                    admin
                        .listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata()
                        .get()
                }
            val behind = ends.filter { (partition, end) -> end > (committed[partition]?.offset() ?: 0L) }
            if (behind.isEmpty()) return ok(Unit)
            if (deadline.hasPassedNow()) {
                return err(UnavailableError("$group が $rawTopic の末尾まで処理しません($waits.timeout を超えた。残り ${behind.size} パーティション)"))
            }
            delay(waits.poll)
        }
    }

    private suspend fun readLatest(scope: ReconcileScope): Result<Map<String, LegacyOrder>, DomainError> {
        val wanted = (scope as? ReconcileScope.Keys)?.orderNumbers
        val values =
            readToEnd()
                .filter { (key, value) -> value != null && (wanted == null || key in wanted) }
                .mapValues { checkNotNull(it.value) }
        return decode(values)
    }

    /** 出力のトピックの今の末尾までを読み、キーごとの最後の値(tombstone は null)を返す。 */
    private suspend fun readToEnd(): Map<String, ByteArray?> {
        val ends = endOffsets(outputTopic)
        val latest = mutableMapOf<String, ByteArray?>()
        withContext(Dispatchers.IO) {
            consumers().use { consumer ->
                consumer.assign(ends.keys)
                consumer.seekToBeginning(ends.keys)
                val deadline = TimeSource.Monotonic.markNow() + waits.timeout
                while (ends.any { (partition, end) -> consumer.position(partition) < end }) {
                    check(deadline.hasNotPassedNow()) { "$outputTopic を末尾まで読めません" }
                    consumer
                        .poll(POLL.toJavaDuration())
                        .filter { it.offset() < ends.getValue(TopicPartition(it.topic(), it.partition())) && it.key() != null }
                        .forEach { latest[checkNotNull(it.key()).toString(Charsets.UTF_8)] = it.value() }
                }
            }
        }
        return latest
    }

    @Suppress("ReturnCount") // 読めない値・解釈できない値のそれぞれで、以降を読まずに返す
    private suspend fun decode(values: Map<String, ByteArray>): Result<Map<String, LegacyOrder>, DomainError> {
        val result = mutableMapOf<String, LegacyOrder>()
        for ((key, value) in values) {
            val event =
                when (val decoded = deserializer.deserialize(value)) {
                    is Result.Err -> return err(decoded.error.asDomainError())
                    is Result.Ok -> decoded.value
                }
            result[key] = event.toDomain() ?: return err(UnexpectedError("$outputTopic の $key の値を解釈できません"))
        }
        return ok(result)
    }

    private suspend fun endOffsets(topic: String): Map<TopicPartition, Long> =
        withContext(Dispatchers.IO) {
            val partitions =
                admin
                    .describeTopics(listOf(topic))
                    .allTopicNames()
                    .get()
                    .getValue(topic)
                    .partitions()
                    .map { TopicPartition(topic, it.partition()) }
            admin
                .listOffsets(partitions.associateWith { OffsetSpec.latest() })
                .all()
                .get()
                .mapValues { it.value.offset() }
        }

    private companion object {
        val POLL = 200.milliseconds
    }
}
