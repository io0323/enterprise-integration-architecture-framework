@file:Suppress("MagicNumber") // テストデータの値・待ち時間

package io.eia.platform.messagingkafka

import io.eia.platform.inbox.Inbox
import io.eia.platform.inbox.InboxSchema
import io.eia.platform.inbox.InboxStorageUnavailable
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.SecureRandom
import java.sql.SQLException
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private val SCHEMA =
    """
    {"type":"record","name":"ParcelScanned","namespace":"io.eia.events.test","fields":[
      {"name":"parcelId","type":"string"},
      {"name":"location","type":"string"}
    ]}
    """.trimIndent()

@Serializable
@SerialName("io.eia.events.test.ParcelScanned")
private data class ParcelScanned(
    val parcelId: String,
    val location: String,
)

private const val GROUP = "tracking.scan"
private const val OWNER_ROLE = "tracking"
private const val APP_ROLE = "tracking_app"

/**
 * [EventConsumer] と冪等消費の記録(platform/inbox)を、実際の Kafka・Apicurio Registry・PostgreSQL で確かめる(ADR-0028)。
 *
 * 処理(サービスの adapters と同じ形): 1 つのトランザクションで processed_message に記録し、初めてなら業務の表(`tracking.scan`)に 1 行を書く。
 * DB に接続できない・一時的に使えないときは Unavailable、それ以外の DB の拒否は Rejected。
 */
class EventConsumerIT :
    FunSpec({
        val registry = ApicurioRegistryContainer().also { it.start() }
        val kafka =
            KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
                .also { it.start() }
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres")).also { it.start() }
        val http = HttpClient(CIO)
        val client = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), http)
        val runtime = Observability.init(ObservabilityConfig.of("consumer-it").ok(), TelemetrySinks(), installLogAppender = false)
        val admin = Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers))
        val dlqProducer =
            KafkaProducer<ByteArray, ByteArray>(KafkaProducerSettings(kafka.bootstrapServers, "consumer-it-dlq").toProperties())
        val eventProducer = KafkaProducer<ByteArray, ByteArray>(KafkaProducerSettings(kafka.bootstrapServers, "consumer-it").toProperties())
        val topics = AtomicInteger()
        val appPassword = randomHex()
        val ownerPassword = randomHex()

        fun dataSource(
            user: String,
            password: String,
        ) = PGSimpleDataSource().apply {
            setURL("jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}")
            this.user = user
            this.password = password
            // DB を止めたときに、接続の待ちが長引かないようにする
            connectTimeout = 2
            socketTimeout = 5
            loginTimeout = 2
        }
        val superuser = dataSource(postgres.username, postgres.password)
        val app = dataSource(APP_ROLE, appPassword)

        superuser.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $OWNER_ROLE LOGIN PASSWORD '$ownerPassword'")
                statement.execute("CREATE ROLE $APP_ROLE LOGIN PASSWORD '$appPassword'")
                statement.execute("GRANT CREATE ON DATABASE ${postgres.databaseName} TO $OWNER_ROLE")
            }
        }
        InboxSchema.migrate(dataSource(OWNER_ROLE, ownerPassword), APP_ROLE).ok()
        dataSource(OWNER_ROLE, ownerPassword).connection.use { connection ->
            connection.createStatement().use { statement ->
                // PostgreSQL 15 以降は public に一般のロールが作れないため、所有者のスキーマに作る(サービスの DB と同じ)
                statement.execute("CREATE SCHEMA tracking")
                statement.execute("CREATE TABLE tracking.scan (message_id uuid NOT NULL, parcel_id text NOT NULL)")
                statement.execute("GRANT USAGE ON SCHEMA tracking TO $APP_ROLE")
                statement.execute("GRANT INSERT ON tracking.scan TO $APP_ROLE")
            }
        }

        afterSpec {
            admin.close()
            dlqProducer.close()
            eventProducer.close()
            runtime.close()
            http.close()
            postgres.stop()
            kafka.stop()
            registry.stop()
        }

        /** テストごとのトピック(本流と DLQ)を作り、契約を登録する。 */
        suspend fun newTopic(): EventTopic {
            val topic = EventTopic.of("test.parcel.scanned-${topics.incrementAndGet()}.v1")
            admin
                .createTopics(listOf(NewTopic(topic.name, 2, 1), NewTopic(DeadLetterPublisher.deadLetterTopic(topic.name), 2, 1)))
                .all()
                .get()
            client.register(SchemaSubject(topic.name, SCHEMA)).ok()
            return topic
        }

        suspend fun publish(
            topic: EventTopic,
            vararg scans: ParcelScanned,
        ) {
            val subject = SchemaSubject(topic.name, SCHEMA)
            val serializer =
                AvroEventSerializer(
                    topic,
                    subject,
                    ParcelScanned.serializer(),
                    SchemaIdBook(listOf(subject), client).also { it.resolve().ok() },
                )
            val producer = EventProducer(eventProducer, runtime, "/test/scanner")
            scans.forEach { producer.send(serializer, it.parcelId, it).ok() }
        }

        fun scans(parcelId: String): Long =
            superuser.connection.use { connection ->
                connection.prepareStatement("SELECT count(*) FROM tracking.scan WHERE parcel_id = ?").use { statement ->
                    statement.setString(1, parcelId)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
            }

        /** サービスの adapters と同じ形の処理。[outcomes] に結果を記録する。 */
        fun handler(outcomes: MutableList<Handled>): EventHandler<ParcelScanned> =
            EventHandler { event ->
                withContext(Dispatchers.IO) {
                    try {
                        app.connection.use { connection ->
                            connection.autoCommit = false
                            when (val receipt = Inbox().markProcessed(connection, GROUP, event.metadata.id, event.topic)) {
                                is Result.Err -> {
                                    connection.rollback()
                                    val error = receipt.error
                                    val failure =
                                        if (error is InboxStorageUnavailable) {
                                            HandlingFailure.Unavailable(error.code, error.message)
                                        } else {
                                            HandlingFailure.Rejected(error.code, error.message)
                                        }
                                    Result.Err(failure)
                                }

                                is Result.Ok -> {
                                    if (receipt.value == Inbox.Receipt.FIRST) {
                                        connection.prepareStatement("INSERT INTO tracking.scan VALUES (?, ?)").use { statement ->
                                            statement.setObject(1, java.util.UUID.fromString(event.metadata.id.toString()))
                                            statement.setString(2, event.value.parcelId)
                                            statement.executeUpdate()
                                        }
                                    }
                                    connection.commit()
                                    val handled = if (receipt.value == Inbox.Receipt.FIRST) Handled.PROCESSED else Handled.DUPLICATE
                                    synchronized(outcomes) { outcomes += handled }
                                    Result.Ok(handled)
                                }
                            }
                        }
                    } catch (e: SQLException) {
                        // 接続できない(DB の停止)
                        Result.Err(HandlingFailure.Unavailable("db_unavailable", "SQLSTATE ${e.sqlState}"))
                    }
                }
            }

        class Running(
            val consumer: EventConsumer,
            val job: Job,
        )

        /** 1 つのスレッドの Dispatcher で Consumer を動かす(app と同じ)。 */
        fun start(
            topic: EventTopic,
            outcomes: MutableList<Handled>,
            committer: OffsetCommitter = OffsetCommitter { c, offsets -> c.commitSync(offsets) },
        ): Running {
            val kafkaConsumer =
                KafkaConsumer<ByteArray?, ByteArray?>(
                    EventConsumer.consumerProperties(kafka.bootstrapServers, GROUP + topic.name.filter(Char::isDigit), "consumer-it"),
                )
            val subscription =
                EventSubscription(
                    topic,
                    AvroEventDeserializer(ParcelScanned.serializer(), WriterSchemas(client)),
                    "INT-TEST-001",
                    handler(outcomes),
                )
            val consumer =
                EventConsumer(
                    kafkaConsumer,
                    GROUP + topic.name.filter(Char::isDigit),
                    listOf(subscription),
                    DeadLetterPublisher(dlqProducer),
                    runtime,
                    unavailableRetry = EventConsumer.UNAVAILABLE_RETRY.copy(maxDelay = 1.seconds),
                    committer = committer,
                )
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val job = CoroutineScope(SupervisorJob() + dispatcher).launch { consumer.run() }
            job.invokeOnCompletion { dispatcher.close() }
            return Running(consumer, job)
        }

        suspend fun waitUntil(
            description: String,
            timeout: kotlin.time.Duration = 60.seconds,
            condition: () -> Boolean,
        ) {
            val started = TimeSource.Monotonic.markNow()
            while (!condition()) {
                if (started.elapsedNow() > timeout) fail("$description を $timeout 待ちましたが、満たされませんでした")
                delay(200.milliseconds)
            }
        }

        fun deadLetters(topic: EventTopic): List<Map<String, String>> {
            val dlq = DeadLetterPublisher.deadLetterTopic(topic.name)
            val properties =
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "consumer-it.${Uuid.random()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
            return KafkaConsumer(properties, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
                val partitions = consumer.partitionsFor(dlq).map { TopicPartition(dlq, it.partition()) }
                consumer.assign(partitions)
                val end = consumer.endOffsets(partitions)
                buildList {
                    while (partitions.any { consumer.position(it) < end.getValue(it) }) {
                        consumer.poll(Duration.ofMillis(500)).forEach { record ->
                            add(record.headers().associate { it.key() to String(it.value()) } + ("key" to String(record.key())))
                        }
                    }
                }
            }
        }

        test("Poison Message は DLQ に隔離され、同じパーティションの後続のメッセージは止まらずに処理される") {
            val topic = newTopic()
            val outcomes = mutableListOf<Handled>()
            publish(topic, ParcelScanned("p-1", "tokyo"))
            // 同じキー(同じパーティション)に、Avro として読めない値を送る
            val poison = ProducerRecord<ByteArray, ByteArray>(topic.name, "p-1".toByteArray(), byteArrayOf(9, 9, 9))
            poison.headers().let { headers -> EventMetadataFixtures.headers(topic).forEach { headers.add(it) } }
            eventProducer.send(poison).get()
            publish(topic, ParcelScanned("p-1", "osaka"))

            val running = start(topic, outcomes)
            try {
                waitUntil("2 件の処理") { scans("p-1") == 2L }
                val dead = deadLetters(topic).single()
                dead["eiaf.dlq.reason"] shouldBe EventConsumer.UNDECODABLE
                dead["eiaf.dlq.attempts"] shouldBe "1"
                dead["eiaf.dlq.source.topic"] shouldBe topic.name
                dead["key"] shouldBe "p-1"
                running.consumer.ready shouldBe true
            } finally {
                running.job.cancelAndJoin()
            }
        }

        test("処理の確定の後・オフセットのコミットの前に落ちても、再起動の後は重複として捨て、業務の処理は 1 回だけ") {
            val topic = newTopic()
            val first = mutableListOf<Handled>()
            publish(topic, ParcelScanned("p-2", "a"), ParcelScanned("p-2", "b"), ParcelScanned("p-2", "c"))

            // 最初のコミットで落ちる(処理は確定している)
            val crashing = start(topic, first) { _, _ -> throw IllegalStateException("コミットの前に落ちた") }
            crashing.job.join()
            synchronized(first) { first.toList() } shouldBe List(first.size) { Handled.PROCESSED }
            (first.size >= 1) shouldBe true

            val second = mutableListOf<Handled>()
            val running = start(topic, second)
            try {
                waitUntil("3 件の受信") { synchronized(second) { second.size } + first.size >= 3 && scans("p-2") == 3L }
                // 落ちる前に処理した分は、送り直されても重複になる
                waitUntil("送り直しの処理") { synchronized(second) { second.count { it == Handled.DUPLICATE } } == first.size }
                scans("p-2") shouldBe 3L
                deadLetters(topic).size shouldBe 0
            } finally {
                running.job.cancelAndJoin()
            }
        }

        test("DB が止まっている間は DLQ に送らずに読み直し(ready=false)、回復すれば続きから処理する") {
            val topic = newTopic()
            val outcomes = mutableListOf<Handled>()
            val running = start(topic, outcomes)
            try {
                publish(topic, ParcelScanned("p-3", "a"))
                waitUntil("1 件目の処理") { scans("p-3") == 1L }

                val docker = postgres.dockerClient
                docker.pauseContainerCmd(postgres.containerId).exec()
                try {
                    publish(topic, ParcelScanned("p-3", "b"), ParcelScanned("p-3", "c"))
                    waitUntil("ready が false になる") { !running.consumer.ready }
                    // 止まっている間も Consumer は動き続け、メッセージを DLQ に送らない
                    delay(3.seconds)
                    running.job.isActive shouldBe true
                    running.consumer.ready shouldBe false
                } finally {
                    docker.unpauseContainerCmd(postgres.containerId).exec()
                }

                waitUntil("回復の後の処理") { scans("p-3") == 3L }
                waitUntil("ready が true に戻る") { running.consumer.ready }
                deadLetters(topic).size shouldBe 0
                synchronized(outcomes) { outcomes.count { it == Handled.PROCESSED } } shouldBe 3
            } finally {
                running.job.cancelAndJoin()
            }
        }
    })

/** Poison Message にも、正しい CloudEvents のヘッダを付ける(値だけが読めない場合を作る)。 */
private object EventMetadataFixtures {
    fun headers(topic: EventTopic) =
        EventMetadata(
            id = Uuid.random(),
            source = "/test/scanner",
            type = topic.ceType,
            time =
                kotlin.time.Clock.System
                    .now(),
            traceParent =
                io.eia.shared.resilience.trace.TraceParent
                    .parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                    .ok(),
            correlationId =
                io.eia.shared.kernel.CorrelationId
                    .generate(),
        ).toHeaders()
}

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
