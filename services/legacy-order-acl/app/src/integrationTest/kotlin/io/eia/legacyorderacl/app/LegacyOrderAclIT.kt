@file:Suppress("MagicNumber") // 件数・待ち時間・パーティション・時刻

package io.eia.legacyorderacl.app

import io.eia.legacyorderacl.adapters.inbound.LegacyChangeConsumer
import io.eia.legacyorderacl.adapters.inbound.OffsetCommitter
import io.eia.legacyorderacl.adapters.outbound.LegacyOrderEventSchemas
import io.eia.platform.messagingkafka.ApicurioWireFormat
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.ContentId
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.testcontainers.kafka.KafkaContainer
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.readLines
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import java.net.http.HttpClient as JdkHttpClient

private const val RAW_TOPIC = LegacyChangeConsumer.TOPIC
private const val DLQ_TOPIC = "$RAW_TOPIC.dlq"
private const val OUTPUT_TOPIC = "sales.legacy-order.changed.v1"

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private val repositoryRoot: Path = Path.of(checkNotNull(System.getProperty("eia.repositoryRoot")) { "Gradle の Test タスクから実行してください" })

/** Converter が登録する生の CDC のスキーマ(参照なし。adapters のテストと同じファイル)。 */
private val envelopeSchema: Schema =
    Schema.Parser().parse(
        repositoryRoot.resolve("services/legacy-order-acl/adapters/src/test/resources/debezium/t_juchu-envelope.avsc").toFile(),
    )

/** 出力の 1 件(値がなければ tombstone)。 */
private data class Output(
    val key: String,
    val status: String?,
    val record: ConsumerRecord<String, ByteArray?>,
)

/**
 * legacy-order-acl を、ローカル基盤と同じトピックの定義(infra/local/kafka/topics.conf)と、Converter と同じ形式の生の CDC で確かめる
 * (ADR-0026 §5〜§7・§9)。Debezium を含めた全体は e2e(LegacyOrderAclE2E)で確かめる。
 *
 * - 生の CDC は、Converter と同じく Apicurio のグループ cdc-raw に登録したスキーマの contentId で、Apicurio の wire format で書く。
 * - 出力の契約のスキーマは schema-publish と同じく、グループ default に登録する。
 */
class LegacyOrderAclIT :
    FunSpec({
        val kafka =
            KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
        val registry = ApicurioRegistryContainer()
        val http = JdkHttpClient.newHttpClient()
        var envelopeContentId = ContentId(0)
        val running = mutableListOf<AclServer>()

        fun start(
            group: String,
            committer: OffsetCommitter? = null,
        ): AclServer {
            val env =
                mapOf(
                    AclConfig.KAFKA_BOOTSTRAP to kafka.bootstrapServers,
                    AclConfig.SCHEMA_REGISTRY_URL to registry.baseUrl,
                    AclConfig.GROUP_ID to group,
                    AclConfig.HEALTH_PORT to "0",
                    "EIA_LOG_FORMAT" to "console",
                )
            return AclServer.start(env, committer).ok().also { running += it }
        }

        fun stop(server: AclServer) {
            server.stop()
            running -= server
        }

        fun ready(server: AclServer): Int =
            http
                .send(
                    HttpRequest.newBuilder(URI.create("http://localhost:${server.healthPort}/health/ready")).build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()

        fun awaitReady(server: AclServer) {
            val deadline = TimeSource.Monotonic.markNow() + 60.seconds
            while (ready(server) != 200) {
                check(deadline.hasNotPassedNow()) { "legacy-order-acl が ready になりません" }
                Thread.sleep(200)
            }
        }

        val producer by lazy {
            KafkaProducer(
                mapOf<String, Any>(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers, ProducerConfig.ACKS_CONFIG to "all"),
                ByteArraySerializer(),
                ByteArraySerializer(),
            )
        }

        /** 生の CDC に、Debezium の Envelope を書く(キーは注文番号。Converter と同じ wire format)。 */
        fun change(
            op: String,
            number: String,
            status: String = "1",
            lsn: Long = 1_000L,
        ): RecordMetadata {
            val rowSchema =
                envelopeSchema
                    .getField("before")
                    .schema()
                    .types
                    .first { it.type == Schema.Type.RECORD }
            val row =
                GenericData.Record(rowSchema).apply {
                    put("col_01", "1")
                    put("col_02", number)
                    put("col_03", status)
                    put("col_04", "山田商事株式会社".padEnd(40))
                    put("col_05", "C0000101")
                    put("col_06", "1200.00")
                    put("col_07", 1_791_450_000_000_000L)
                    put("col_08", 253_402_214_400_000_000L)
                }
            val envelope =
                GenericData.Record(envelopeSchema).apply {
                    put(
                        "source",
                        GenericData.Record(envelopeSchema.getField("source").schema()).apply {
                            put("version", "3.6.3.Final")
                            put("connector", "postgresql")
                            put("name", "_cdc.legacy")
                            put("ts_ms", 1_791_454_000_123L)
                            put("snapshot", "false")
                            put("db", "legacy_sim")
                            put("schema", "public")
                            put("table", "t_juchu")
                            put("lsn", lsn)
                        },
                    )
                    if (op == "d") put("before", row) else put("after", row)
                    put("op", op)
                }
            val bytes =
                ByteArrayOutputStream().use { out ->
                    val encoder = EncoderFactory.get().binaryEncoder(out, null)
                    GenericDatumWriter<GenericData.Record>(envelopeSchema).write(envelope, encoder)
                    encoder.flush()
                    ApicurioWireFormat.frame(envelopeContentId, out.toByteArray())
                }
            return producer.send(ProducerRecord(RAW_TOPIC, number.toByteArray(), bytes)).get()
        }

        val outputSchema = Schema.Parser().parse(LegacyOrderEventSchemas.legacyOrderChanged.schema)

        /** [topic] を最初から読み、[done] が真になるまで([timeout] まで)読んだレコードを返す。 */
        fun read(
            topic: String,
            timeout: kotlin.time.Duration = 60.seconds,
            done: (List<ConsumerRecord<String, ByteArray?>>) -> Boolean,
        ): List<ConsumerRecord<String, ByteArray?>> {
            val properties =
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "acl-it.${Uuid.random()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
            val received = mutableListOf<ConsumerRecord<String, ByteArray?>>()
            KafkaConsumer(properties, StringDeserializer(), ByteArrayDeserializer()).use { consumer ->
                consumer.subscribe(listOf(topic))
                val deadline = TimeSource.Monotonic.markNow() + timeout
                while (!done(received)) {
                    check(deadline.hasNotPassedNow()) { "$topic に期待したレコードが届きません: ${received.map { it.key() }}" }
                    received += consumer.poll(Duration.ofMillis(500))
                }
            }
            return received
        }

        fun outputs(keys: Set<String>): (List<ConsumerRecord<String, ByteArray?>>) -> List<Output> =
            { records ->
                records.filter { it.key() in keys }.map { record ->
                    val status =
                        record.value()?.let { value ->
                            val framed = ApicurioWireFormat.parse(value).ok()
                            GenericDatumReader<GenericRecord>(outputSchema)
                                .read(null, DecoderFactory.get().binaryDecoder(framed.avroBinary, null))
                                .get("status")
                                .toString()
                        }
                    Output(record.key(), status, record)
                }
            }

        beforeSpec {
            kafka.start()
            registry.start()
            // ローカル基盤と同じトピック(topics.conf)と、Debezium が作る生のトピック
            Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)).use { admin ->
                val defined =
                    repositoryRoot
                        .resolve("infra/local/kafka/topics.conf")
                        .readLines()
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .map { line ->
                            val (name, partitions, configs) = line.trim().split(Regex("\\s+"))
                            NewTopic(name, partitions.toInt(), 1.toShort()).configs(
                                configs.split(',').associate {
                                    it.substringBefore('=') to
                                        it.substringAfter('=')
                                },
                            )
                        }
                admin.createTopics(defined + NewTopic(RAW_TOPIC, 3, 1.toShort())).all().get()
            }
            HttpClient(CIO).use { client ->
                runBlocking {
                    // schema-publish と同じ(契約のスキーマ)
                    ApicurioRegistryClient(
                        SchemaRegistryConfig(registry.baseUrl),
                        client,
                    ).register(LegacyOrderEventSchemas.legacyOrderChanged).ok()
                    // Converter と同じ(グループ cdc-raw。参照なしの 1 つのスキーマ)
                    envelopeContentId =
                        ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, groupId = "cdc-raw"), client)
                            .register(SchemaSubject(RAW_TOPIC, envelopeSchema.toString()))
                            .ok()
                }
            }
        }

        afterSpec {
            running.toList().forEach(::stop)
            producer.close()
            registry.stop()
            kafka.stop()
        }

        test("登録・更新・削除を、注文番号をキーにした最新の状態と tombstone にする。同じ注文の変更は届いた順に出る") {
            val server = start("acl-it.order")
            awaitReady(server)
            change("c", "A0001", "1", lsn = 100)
            change("u", "A0001", "2", lsn = 110)
            change("c", "A0002", "1", lsn = 120)
            change("u", "A0001", "3", lsn = 130)
            change("d", "A0002", lsn = 140)

            val keys = setOf("A0001", "A0002")
            val out = outputs(keys)(read(OUTPUT_TOPIC) { outputs(keys)(it).size >= 5 })
            out.filter { it.key == "A0001" }.map { it.status } shouldBe listOf("ACCEPTED", "ALLOCATED", "SHIPPED")
            out.filter { it.key == "A0002" }.map { it.status } shouldBe listOf("ACCEPTED", null)
            out.forEach { output ->
                val metadata = EventMetadata.fromHeaders(output.record.headers()).ok()
                metadata.source shouldBe "/sales/legacy-order-acl"
                metadata.type shouldBe "sales.legacy-order.changed"
            }
            stop(server)
        }

        test("変換できない変更は DLQ に送り、同じ注文の後の変更は通常どおり出す。DLQ と出力のトピックは topics.conf の設定") {
            val server = start("acl-it.dlq")
            awaitReady(server)
            change("c", "B0001", "7", lsn = 200)
            change("u", "B0001", "2", lsn = 210)

            val dead = read(DLQ_TOPIC) { records -> records.any { it.key() == "B0001" } }.first { it.key() == "B0001" }
            String(dead.headers().lastHeader("eiaf.dlq.reason").value()) shouldBe "UNKNOWN_STATUS_CODE"
            String(dead.headers().lastHeader("eiaf.dlq.source.topic").value()) shouldBe RAW_TOPIC
            ApicurioWireFormat.parse(checkNotNull(dead.value())).ok().contentId shouldBe envelopeContentId

            val keys = setOf("B0001")
            outputs(keys)(read(OUTPUT_TOPIC) { outputs(keys)(it).isNotEmpty() }).map { it.status } shouldBe listOf("ALLOCATED")

            Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)).use { admin ->
                val resources = listOf(DLQ_TOPIC, OUTPUT_TOPIC).map { ConfigResource(ConfigResource.Type.TOPIC, it) }
                val configs = admin.describeConfigs(resources).all().get()
                configs.getValue(resources[0]).get("retention.ms").value() shouldBe "604800000"
                configs.getValue(resources[0]).get("cleanup.policy").value() shouldBe "delete"
                configs.getValue(resources[1]).get("cleanup.policy").value() shouldBe "compact"
                configs.getValue(resources[1]).get("delete.retention.ms").value() shouldBe "86400000"
            }
            stop(server)
        }

        test("発行の後・オフセットのコミットの前に止まっても、再起動の後は重複だけで欠けず、最後の状態は最新(At-Least-Once)") {
            val keys = setOf("C0001", "C0002")
            val written =
                listOf(
                    change("c", "C0001", "1", lsn = 300),
                    change("u", "C0001", "2", lsn = 310),
                    change("c", "C0002", "1", lsn = 320),
                    change("u", "C0001", "9", lsn = 330),
                )
            // パーティションごとの、この 4 件の最後の位置
            val last = written.groupBy { TopicPartition(it.topic(), it.partition()) }.mapValues { (_, list) -> list.maxOf { it.offset() } }

            // 4 件を発行し終えた後のコミットで止める(コミットはしない)。それより前のバッチのコミットは通常どおり行う
            val crashed = AtomicBoolean(false)
            val crashing =
                start("acl-it.restart") { consumer, offsets ->
                    val coversAll =
                        last.all { (partition, offset) ->
                            (
                                offsets[partition]?.offset()
                                    ?: consumer.committed(setOf(partition))[partition]?.offset()
                                    ?: 0
                            ) >
                                offset
                        }
                    if (coversAll) {
                        crashed.set(true)
                        error("発行の後・コミットの前に止まった(模擬)")
                    }
                    consumer.commitSync(offsets)
                }
            val deadline = TimeSource.Monotonic.markNow() + 60.seconds
            while (!crashed.get()) {
                check(deadline.hasNotPassedNow()) { "コミットまで進みません" }
                Thread.sleep(100)
            }
            stop(crashing)
            val beforeRestart = outputs(keys)(read(OUTPUT_TOPIC) { outputs(keys)(it).isNotEmpty() })

            val restarted = start("acl-it.restart")
            awaitReady(restarted)
            val out = outputs(keys)(read(OUTPUT_TOPIC) { records -> outputs(keys)(records).size >= beforeRestart.size + 4 })

            // 欠けない: 4 件の変更はすべて出ている。重複する: 止まる前に出した分を、再起動の後に送り直した
            out.map { it.key to it.status } shouldContainAll
                listOf("C0001" to "ACCEPTED", "C0001" to "ALLOCATED", "C0002" to "ACCEPTED", "C0001" to "CANCELLED")
            out.size shouldBeGreaterThan 4
            // 最後の状態は最新(compacted の Upsert として冪等)
            out.last { it.key == "C0001" }.status shouldBe "CANCELLED"
            out.last { it.key == "C0002" }.status shouldBe "ACCEPTED"
            stop(restarted)
        }
    })
