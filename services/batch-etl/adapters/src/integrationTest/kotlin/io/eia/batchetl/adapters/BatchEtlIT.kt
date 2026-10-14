@file:Suppress("MagicNumber") // テストデータの金額・件数・日数

package io.eia.batchetl.adapters

import io.eia.batchetl.adapters.kafka.KafkaIngestPositions
import io.eia.batchetl.adapters.kafka.KafkaOrderEventSource
import io.eia.batchetl.adapters.kafka.OrderCancelledV1
import io.eia.batchetl.adapters.kafka.OrderCreatedV1
import io.eia.batchetl.adapters.kafka.OrderStatusChangedV1
import io.eia.batchetl.adapters.kafka.OrderStatusV1
import io.eia.batchetl.adapters.persistence.BatchEtlSchema
import io.eia.batchetl.adapters.persistence.JdbcMartSummaries
import io.eia.batchetl.adapters.persistence.JdbcOrderMart
import io.eia.batchetl.adapters.persistence.JdbcRawOrderStore
import io.eia.batchetl.application.port.inbound.IngestReport
import io.eia.batchetl.application.port.outbound.RawOrderStore
import io.eia.batchetl.application.port.outbound.SourcePosition
import io.eia.batchetl.application.usecase.BuildDailyOrderMartService
import io.eia.batchetl.application.usecase.IngestOrderEventsService
import io.eia.batchetl.domain.BusinessDay
import io.eia.batchetl.domain.DailyOrderDigest
import io.eia.batchetl.domain.DailySummary
import io.eia.batchetl.domain.DayReconciliation
import io.eia.batchetl.domain.LateDataPolicy
import io.eia.batchetl.domain.OrderRecord
import io.eia.batchetl.domain.OrderStatus
import io.eia.platform.batch.BatchRunner
import io.eia.platform.batch.PartitionKey
import io.eia.platform.batch.RunKey
import io.eia.platform.batch.RunOutcome
import io.eia.platform.batch.RunStatus
import io.eia.platform.batch.StepName
import io.eia.platform.batch.dag
import io.eia.platform.batch.jdbc.JdbcBatchStore
import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.messagingkafka.KafkaProducerSettings
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
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnexpectedError
import io.eia.shared.kernel.getOrNull
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.RecordsToDelete
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * batch-etl の取り込み(ETL: 住所を地域にしてから入れる)・日次のパーティションの置換(ELT)・遅れて届いたデータ・kill と再実行を、
 * 実際の PostgreSQL・Kafka・Apicurio Registry で確かめる(ADR-0031 §4・§5。P08 の DoD)。
 */
class BatchEtlIT :
    FunSpec({
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        val registry = ApicurioRegistryContainer()
        val kafka =
            KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
        val runtime = Observability.init(ObservabilityConfig.of("batch-etl-it").getOrNull()!!, TelemetrySinks(), installLogAppender = false)
        val ktor = HttpClient(CIO)
        val ownerPassword = randomHex()
        val appPassword = randomHex()
        val databases = mutableListOf<String>()
        lateinit var producer: KafkaProducer<ByteArray, ByteArray>
        lateinit var writerSchemas: WriterSchemas
        lateinit var created: AvroEventSerializer<OrderCreatedV1>
        lateinit var cancelled: AvroEventSerializer<OrderCancelledV1>
        lateinit var statusChanged: AvroEventSerializer<OrderStatusChangedV1>
        val dayD = BusinessDay.parse("2026-10-13").getOrNull()!!

        /** テストごとの環境(DB とトピックを分ける)。トピックはテストごとに作り直せないので、DB ごとに位置を持つ。 */
        class Env(
            val app: PGSimpleDataSource,
            val batch: JdbcBatchStore,
            val raw: JdbcRawOrderStore,
            val mart: JdbcOrderMart,
            val source: KafkaOrderEventSource,
        ) {
            val ingest = IngestOrderEventsService(source, raw)
            val build = BuildDailyOrderMartService(mart)

            fun query(sql: String): List<List<String?>> =
                app.connection.use { c ->
                    c.createStatement().use { s ->
                        s.executeQuery(sql).use { rs ->
                            buildList {
                                while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) })
                            }
                        }
                    }
                }
        }

        fun superuser(sql: String) {
            PGSimpleDataSource()
                .apply {
                    setURL(postgres.jdbcUrl)
                    user = postgres.username
                    password = postgres.password
                }.connection
                .use { c -> c.createStatement().use { it.execute(sql) } }
        }

        fun dataSource(
            database: String,
            user: String,
            password: String,
        ) = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replaceAfterLast('/', database))
            this.user = user
            this.password = password
        }

        /**
         * @param linguisticCollation true なら DB の既定の照合順序を ICU の言語的な並び(`und`)にする。Alpine の PostgreSQL の既定は
         *   バイト順と同じになるため、並びを明示しない SQL の誤りが見えない(ADR-0031 §3)
         */
        fun env(
            maxPollRecords: Int = 500,
            linguisticCollation: Boolean = false,
        ): Env {
            val database = "batch_etl_${databases.size + 1}".also { databases += it }
            val locale = if (linguisticCollation) " TEMPLATE template0 LOCALE_PROVIDER icu ICU_LOCALE 'und' LOCALE 'C'" else ""
            superuser("CREATE DATABASE $database OWNER batch_etl$locale")
            superuser("REVOKE ALL ON DATABASE $database FROM PUBLIC")
            superuser("GRANT CONNECT ON DATABASE $database TO ${BatchEtlSchema.APP_ROLE}")
            BatchEtlSchema.migrate(dataSource(database, "batch_etl", ownerPassword)).getOrNull()!!
            val app = dataSource(database, BatchEtlSchema.APP_ROLE, appPassword)
            val batch = JdbcBatchStore(app)
            val properties =
                KafkaOrderEventSource.consumerProperties(kafka.bootstrapServers) +
                    (ConsumerConfig.MAX_POLL_RECORDS_CONFIG to maxPollRecords)
            val source = KafkaOrderEventSource({ KafkaConsumer(properties) }, writerSchemas, DeadLetterPublisher(producer))
            return Env(app, batch, JdbcRawOrderStore(app, batch), JdbcOrderMart(app), source)
        }

        val address =
            io.eia.batchetl.adapters.kafka
                .AddressV1("JP", "100-0001", "千代田区", "千代田1-1", "東京都", "皇居外苑ビル 3F")

        fun order(
            id: String,
            orderedAt: String,
            total: Long = 1200,
        ) = OrderCreatedV1(
            io.eia.batchetl.adapters.kafka.OrderV1(
                id = id,
                customerId = "c-$id",
                status = OrderStatusV1.PLACED,
                orderedAt = Instant.parse(orderedAt),
                lines =
                    listOf(
                        io.eia.batchetl.adapters.kafka.OrderLineV1(
                            1,
                            "p-1",
                            "SKU-1",
                            1,
                            io.eia.batchetl.adapters.kafka
                                .MoneyV1(total, "JPY"),
                            io.eia.batchetl.adapters.kafka
                                .MoneyV1(total, "JPY"),
                        ),
                    ),
                totalAmount =
                    io.eia.batchetl.adapters.kafka
                        .MoneyV1(total, "JPY"),
                shippingAddress = address,
            ),
        )

        suspend fun <T> send(
            serializer: AvroEventSerializer<T>,
            key: String,
            value: T,
        ) {
            EventProducer(producer, runtime, "/sales/order-service").send(serializer, key, value).getOrNull() ?: fail("送れません")
        }

        suspend fun placed(
            id: String,
            orderedAt: String,
        ) = send(created, id, order(id, orderedAt))

        suspend fun changed(
            id: String,
            status: OrderStatusV1,
            version: Long,
        ) = send(statusChanged, id, OrderStatusChangedV1(id, status, OrderStatusV1.PLACED, version, Instant.parse("2026-10-14T01:00:00Z")))

        fun <T> Result<T, *>.ok(): T =
            when (this) {
                is Result.Ok -> value
                is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
            }

        beforeSpec {
            postgres.start()
            registry.start()
            kafka.start()
            superuser("CREATE ROLE batch_etl LOGIN PASSWORD '$ownerPassword'")
            superuser("CREATE ROLE ${BatchEtlSchema.APP_ROLE} LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE")
            val client = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), ktor)
            writerSchemas = WriterSchemas(client)
            val contracts =
                Path
                    .of(System.getProperty(InfraImages.FILE_PROPERTY))
                    .parent.parent.parent
                    .resolve("contracts/avro/sales")
            val subjects =
                listOf(
                    SchemaSubject(KafkaOrderEventSource.ORDER_CREATED, contracts.resolve("OrderCreated.avsc").readText()),
                    SchemaSubject(KafkaOrderEventSource.ORDER_CANCELLED, contracts.resolve("OrderCancelled.avsc").readText()),
                    SchemaSubject(KafkaOrderEventSource.ORDER_STATUS_CHANGED, contracts.resolve("OrderStatusChanged.avsc").readText()),
                )
            subjects.forEach { client.register(it).ok() }
            val ids = SchemaIdBook(subjects, client).also { it.resolve().ok() }
            created = AvroEventSerializer(EventTopic.of(KafkaOrderEventSource.ORDER_CREATED), subjects[0], OrderCreatedV1.serializer(), ids)
            cancelled =
                AvroEventSerializer(EventTopic.of(KafkaOrderEventSource.ORDER_CANCELLED), subjects[1], OrderCancelledV1.serializer(), ids)
            statusChanged =
                AvroEventSerializer(
                    EventTopic.of(KafkaOrderEventSource.ORDER_STATUS_CHANGED),
                    subjects[2],
                    OrderStatusChangedV1.serializer(),
                    ids,
                )
            producer = KafkaProducer(KafkaProducerSettings(kafka.bootstrapServers, "batch-etl-it").toProperties())
        }

        afterSpec {
            producer.close()
            ktor.close()
            kafka.stop()
            registry.stop()
            postgres.stop()
        }

        // トピックはテストごとに作る(位置は DB ごとなので、テストごとに最初から読む)
        beforeTest {
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                val existing = admin.listTopics().names().get()
                val topics = KafkaOrderEventSource.TOPICS.flatMap { listOf(it, DeadLetterPublisher.deadLetterTopic(it)) }
                if (existing.isNotEmpty()) admin.deleteTopics(existing.filter { it in topics }).all().get()
                // 削除の完了を待ってから作る
                withTimeout(30.seconds) {
                    while (admin
                            .listTopics()
                            .names()
                            .get()
                            .any { it in topics }
                    ) {
                        kotlinx.coroutines.delay(200)
                    }
                }
                admin.createTopics(topics.map { NewTopic(it, 2, 1) }).all().get()
            }
        }

        test("取り込みは住所を地域にしてから入れ、日本時間の日次のパーティションを置換で作る。読めない値は DLQ に送って先に進む") {
            val env = env()
            // D(2026-10-13 JST)の始まり・終わり直前と、D+1 の始まり(UTC 15:00 が境目)
            placed("o-1", "2026-10-12T15:00:00Z")
            placed("o-2", "2026-10-13T14:59:59Z")
            placed("o-3", "2026-10-13T15:00:00Z")
            changed("o-1", OrderStatusV1.CONFIRMED, 1)
            changed("o-1", OrderStatusV1.SHIPPED, 2)
            changed("o-2", OrderStatusV1.CANCELLED, 1)
            send(cancelled, "o-2", OrderCancelledV1("o-2", Instant.parse("2026-10-13T15:10:00Z"), "customer"))
            producer.send(ProducerRecord(KafkaOrderEventSource.ORDER_CREATED, "bad".toByteArray(), byteArrayOf(9, 9, 9))).get()

            env.ingest().ok() shouldBe IngestReport(loaded = 7, deadLettered = 1)
            env.build(dayD).ok().rebuilt shouldBe listOf(dayD to 2)

            env.query("SELECT order_id, status, cancel_reason, ship_country, ship_region FROM mart.order_daily ORDER BY order_id") shouldBe
                listOf(listOf("o-1", "SHIPPED", null, "JP", "東京都"), listOf("o-2", "CANCELLED", "customer", "JP", "東京都"))
            // 個人情報(市区町村・番地・建物)は分析の DB のどこにもない
            env
                .query("SELECT row_to_json(p)::text FROM raw.order_placed p")
                .flatten()
                .forEach { row -> listOf("千代田", "皇居").forEach { pii -> row!!.contains(pii) shouldBe false } }
            env.query("SELECT ship_postal_prefix FROM raw.order_placed WHERE order_id = 'o-1'") shouldBe listOf(listOf("100"))
            // D+1 は D+1 の実行で作る(印は残る)
            env.query("SELECT business_date::text FROM etl.dirty_day") shouldBe listOf(listOf("2026-10-14"))
            // 読めない値は DLQ に
            KafkaConsumer<ByteArray, ByteArray>(
                mapOf(
                    "bootstrap.servers" to kafka.bootstrapServers,
                    "key.deserializer" to org.apache.kafka.common.serialization.ByteArrayDeserializer::class.java,
                    "value.deserializer" to org.apache.kafka.common.serialization.ByteArrayDeserializer::class.java,
                ),
            ).use { consumer ->
                val dlq = TopicPartition(DeadLetterPublisher.deadLetterTopic(KafkaOrderEventSource.ORDER_CREATED), 0)
                val dlq1 = TopicPartition(dlq.topic(), 1)
                consumer.endOffsets(listOf(dlq, dlq1)).values.sum() shouldBe 1
            }
            // Consumer Group にも位置をコミットしている(lag の監視)
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                val committed =
                    admin
                        .listConsumerGroupOffsets(KafkaOrderEventSource.GROUP_ID)
                        .partitionsToOffsetAndMetadata()
                        .get()
                committed.values.sumOf { it.offset() } shouldBe 8
            }

            // もう一度実行しても何も変わらない(再実行安全)
            env.ingest().ok() shouldBe IngestReport(0, 0)
            env.build(dayD).ok().rebuilt shouldBe listOf(dayD to 2)
        }

        test("DoD: 取り込みの途中で kill して再実行しても、行の重複も欠損もない。位置を消して最初から読み直しても重複しない") {
            val env = env(maxPollRecords = 2)
            for (n in 1..9) placed("k-$n", "2026-10-13T0$n:00:00Z")
            val stores = AtomicStores(env.raw)
            val entered = CompletableDeferred<Unit>()
            val pipeline =
                dag("batch-etl.order-daily") {
                    val ingest = step("ingest") { action { IngestOrderEventsService(env.source, stores)().let(::unit) } }
                    step("transform", ingest) {
                        action { ctx -> env.build(BusinessDay.parse(ctx.run.partition.value).ok()).let(::unit) }
                    }
                }
            val runner = BatchRunner(env.batch, runtime)

            // 2 件ずつの塊を 2 つ確定した後(3 つめの保存の最中)に止める
            stores.hangAfter = 2
            stores.entered = entered
            coroutineScope {
                val killed = launch(Dispatchers.Default) { runner.run(pipeline, PartitionKey("2026-10-13")) }
                withTimeout(60.seconds) { entered.await() }
                killed.cancelAndJoin()
            }
            // 途中まで確定した(全部ではない)
            val partial =
                env
                    .query("SELECT count(*) FROM raw.order_placed")
                    .single()
                    .single()!!
                    .toInt()
            (partial in 1..8) shouldBe true
            env.batch
                .find(RunKey(pipeline.id, PartitionKey("2026-10-13")))
                .getOrNull()!!
                .status shouldBe RunStatus.RUNNING

            stores.hangAfter = Int.MAX_VALUE
            val outcome = runner.run(pipeline, PartitionKey("2026-10-13")).ok().shouldBeInstanceOf<RunOutcome.Succeeded>()
            outcome.executed shouldContainExactly listOf(StepName("ingest"), StepName("transform"))
            env.query("SELECT count(*), count(DISTINCT order_id) FROM raw.order_placed") shouldBe listOf(listOf("9", "9"))
            env.query("SELECT count(*) FROM mart.order_daily WHERE business_date = '2026-10-13'") shouldBe listOf(listOf("9"))

            // 位置を消して最初から読み直しても、行は増えない(冪等)
            env.app.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM batch.batch_checkpoint") } }
            env.ingest().ok().loaded shouldBe 9
            env.build(dayD).ok().rebuilt shouldBe listOf(dayD to 9)
            env.query("SELECT count(*) FROM raw.order_placed") shouldBe listOf(listOf("9"))
        }

        test("遅れて届いたデータ: 31 日前までの変わった日は次の実行で作り直し、それより古い日は作り直さずに印を残す") {
            val env = env()
            placed("l-1", "2026-10-13T01:00:00Z")
            env.ingest().ok()
            env.build(dayD).ok()
            env.query("SELECT status FROM mart.order_daily WHERE order_id = 'l-1'") shouldBe listOf(listOf("PLACED"))

            // D のパーティションを作った後に、D の注文の遷移と、D の遅れて届いた注文と、D-40 の注文が届く
            changed("l-1", OrderStatusV1.DELIVERED, 3)
            placed("l-2", "2026-10-13T02:00:00Z")
            placed("l-old", "2026-09-03T01:00:00Z")
            env.ingest().ok()

            val report = env.build(dayD.minusDays(-1)).ok()

            report.rebuilt shouldBe listOf(dayD to 2, dayD.minusDays(-1) to 0)
            report.tooOld shouldBe listOf(BusinessDay.parse("2026-09-03").ok())
            env.query("SELECT order_id, status FROM mart.order_daily WHERE business_date = '2026-10-13' ORDER BY order_id") shouldBe
                listOf(listOf("l-1", "DELIVERED"), listOf("l-2", "PLACED"))
            env.query("SELECT count(*) FROM mart.order_daily WHERE business_date = '2026-09-03'") shouldBe listOf(listOf("0"))
            env.query("SELECT business_date::text FROM etl.dirty_day") shouldBe listOf(listOf("2026-09-03"))
            LateDataPolicy.REBUILD_WINDOW_DAYS shouldBe 31
        }

        test("照合の集計: 注文 ID のバイト順(UTF-8)で並べた行の SHA-256 と件数。DB の既定が言語的な並びでも変わらない。結果を記録する") {
            val env = env(linguisticCollation = true)
            // 言語的な並び(大文字小文字・記号を区別しない)とバイト順で並びが変わる ID
            val ids = listOf("b-1", "B-2", "_c", "a-1", "é-1")
            for (id in ids) placed(id, "2026-10-13T01:00:00Z")
            changed("a-1", OrderStatusV1.SHIPPED, 2)
            env.ingest().ok()
            env.build(dayD).ok()
            val summaries = JdbcMartSummaries(env.app)

            val summary = summaries.summary(dayD).ok()!!

            val expected =
                ids
                    .sortedWith { a, b -> java.util.Arrays.compareUnsigned(a.toByteArray(), b.toByteArray()) }
                    .joinToString(DailyOrderDigest.LINE_SEPARATOR) { id ->
                        DailyOrderDigest.line(id, if (id == "a-1") OrderStatus.SHIPPED else OrderStatus.PLACED, 1200, "JPY")
                    }
            summary.orderCount shouldBe 5
            summary.digest shouldBe
                java.util.HexFormat
                    .of()
                    .formatHex(
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(expected.toByteArray()),
                    )
            // まだ作っていない日は null。注文のない日は空のバイト列の SHA-256
            summaries.summary(dayD.minusDays(1)).ok() shouldBe null
            env.build(dayD.minusDays(-1)).ok()
            summaries.summary(dayD.minusDays(-1)).ok()!!.digest shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

            val runDay = BusinessDay.parse("2026-10-19").ok()
            summaries
                .record(
                    runDay,
                    listOf(
                        DayReconciliation(dayD, summary, summary),
                        DayReconciliation(dayD.minusDays(1), DailySummary(dayD.minusDays(1), 2, summary.digest), null),
                    ),
                ).ok()
            summaries.record(runDay, listOf(DayReconciliation(dayD, summary.copy(orderCount = 6), summary))).ok()
            env.query(
                "SELECT business_date::text, outcome, source_count, mart_count FROM mart.reconciliation ORDER BY business_date",
            ) shouldBe
                listOf(listOf("2026-10-12", "UNVERIFIED", "2", null), listOf("2026-10-13", "DRIFT", "6", "5"))
        }

        test("取り込んだ位置のイベントが保持期間を過ぎて消えていたら、黙って先に進まずに失敗にする") {
            val env = env()
            placed("r-1", "2026-10-13T01:00:00Z")
            env.ingest().ok()
            placed("r-2", "2026-10-13T02:00:00Z")
            placed("r-3", "2026-10-13T03:00:00Z")
            // 保持期間で消えたことにする: 取り込んだ位置より先まで消す
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                val partitions = (0..1).map { TopicPartition(KafkaOrderEventSource.ORDER_CREATED, it) }
                val ends =
                    admin
                        .listOffsets(
                            partitions.associateWith {
                                org.apache.kafka.clients.admin.OffsetSpec
                                    .latest()
                            },
                        ).all()
                        .get()
                admin.deleteRecords(ends.mapValues { RecordsToDelete.beforeOffset(it.value.offset()) }).all().get()
            }

            val error = env.ingest().shouldBeInstanceOf<Result.Err<DomainError>>().error

            error.shouldBeInstanceOf<UnexpectedError>().message.startsWith("取り込んだ位置のイベントが保持期間を過ぎて消えています") shouldBe true
            env.query("SELECT count(*) FROM raw.order_placed") shouldBe listOf(listOf("1"))

            // 監視: 消えた件数と保持期間が分かる。回復: 消えた範囲を飛ばすと、その後に届いた分から取り込みを続けられる
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                val positions =
                    KafkaIngestPositions({ KafkaConsumer(KafkaOrderEventSource.consumerProperties(kafka.bootstrapServers)) }, admin)
                val stored = env.raw.position().ok()
                val lag =
                    positions
                        .lag(
                            stored,
                            kotlin.time.Clock.System
                                .now(),
                        ).ok()
                lag.lost.values.sum() shouldBe 2
                lag.retention shouldBe 7.days
                lag.oldestPendingAge shouldBe kotlin.time.Duration.ZERO

                placed("r-4", "2026-10-13T04:00:00Z")
                positions
                    .lag(
                        stored,
                        kotlin.time.Clock.System
                            .now(),
                    ).ok()
                    .oldestPendingAge
                    .isPositive() shouldBe true
                val (next, lost) = positions.skipLost(stored).ok()
                lost shouldBe lag.lost
                env.batch
                    .writeCheckpoint(
                        JdbcRawOrderStore.WATERMARK,
                        next.value,
                        kotlin.time.Clock.System
                            .now(),
                    ).ok()
            }
            env.ingest().ok().loaded shouldBe 1
            env.query("SELECT order_id FROM raw.order_placed ORDER BY order_id") shouldBe listOf(listOf("r-1"), listOf("r-4"))
        }
    })

/** [hangAfter] 回の保存の後、次の保存で止まる(kill を作る)。 */
private class AtomicStores(
    private val delegate: RawOrderStore,
) : RawOrderStore {
    @Volatile var hangAfter: Int = Int.MAX_VALUE

    @Volatile var entered: CompletableDeferred<Unit>? = null
    private var appended = 0

    override suspend fun position(): Result<SourcePosition?, DomainError> = delegate.position()

    override suspend fun append(
        records: List<OrderRecord>,
        position: SourcePosition,
    ): Result<Unit, DomainError> {
        if (appended >= hangAfter) {
            entered?.complete(Unit)
            awaitCancellation()
        }
        appended++
        return delegate.append(records, position)
    }
}

private fun unit(result: Result<*, DomainError>): Result<Unit, DomainError> =
    when (result) {
        is Result.Ok -> Result.Ok(Unit)
        is Result.Err -> result
    }

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
