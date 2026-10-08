@file:Suppress("MagicNumber") // 件数・待ち時間・時刻

package io.eia.legacyorderacl.app

import io.eia.legacyorderacl.adapters.outbound.LegacyOrderEventSchemas
import io.eia.legacyorderacl.adapters.reconcile.JdbcLegacySource
import io.eia.legacyorderacl.adapters.reconcile.KafkaPublishedLegacyOrders
import io.eia.legacyorderacl.adapters.reconcile.PublishedReaderSettings
import io.eia.legacyorderacl.adapters.reconcile.ReconcileWaits
import io.eia.legacyorderacl.adapters.reconcile.Sha256Fingerprints
import io.eia.legacyorderacl.application.port.inbound.Mismatch
import io.eia.legacyorderacl.application.port.inbound.ReconciliationReport
import io.eia.legacyorderacl.application.port.outbound.LegacySource
import io.eia.legacyorderacl.application.port.outbound.ReconcileScope
import io.eia.legacyorderacl.application.port.outbound.SourceSnapshot
import io.eia.legacyorderacl.application.usecase.ReconcileLegacyOrdersService
import io.eia.platform.messagingkafka.ApicurioWireFormat
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.ContentId
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.KafkaConnectContainer
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.io.EncoderFactory
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.Network
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.util.HexFormat
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import java.net.http.HttpClient as JdkHttpClient

private const val OUTPUT_TOPIC = "sales.legacy-order.changed.v1"
private const val CONNECTOR = "legacy-juchu"

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))

private val repositoryRoot: Path = Path.of(checkNotNull(System.getProperty("eia.repositoryRoot")) { "Gradle の Test タスクから実行してください" })

/**
 * 照合(ADR-0027)を、レガシーの DB(legacy-sim の V1〜V3 の SQL)→ Debezium(compose と同じ Connect のイメージとコネクタの設定)
 * → legacy-order-acl → 整形済みのトピック の全体で確かめる。照合のユースケースは、本番と同じアダプタで組み立て、
 * 時点のずれを作るためのフック(読み取りの後・待ちの前)だけをテストで差し込む。
 */
class ReconcileIT :
    FunSpec({
        val network = Network.newNetwork()
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical")
                .withNetwork(network)
                .withNetworkAliases("postgres")
        val registry = ApicurioRegistryContainer().withNetwork(network).withNetworkAliases("apicurio")
        val kafka =
            KafkaContainer(InfraImages.get("KAFKA_IMAGE"))
                .withNetwork(network)
                .withListener("kafka:19092")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms384m -Xmx384m")
                .withStartupTimeout(Duration.ofMinutes(3))
        val ownerPassword = randomHex()
        val appPassword = randomHex()
        val cdcPassword = randomHex()
        val reconcilePassword = randomHex()
        val connect = KafkaConnectContainer("kafka:19092", mapOf("DEBEZIUM_DB_PASSWORD" to cdcPassword)).withNetwork(network)
        val http = JdkHttpClient.newHttpClient()
        val registryHttp = HttpClient(CIO)
        lateinit var acl: AclServer

        // ACL と reconcile のサブコマンドの環境(コンテナの起動の後に決まる)
        val aclEnv by lazy {
            mapOf(
                AclConfig.KAFKA_BOOTSTRAP to kafka.bootstrapServers,
                AclConfig.SCHEMA_REGISTRY_URL to registry.baseUrl,
                AclConfig.HEALTH_PORT to "0",
                ReconcileConfig.DB_URL to postgres.jdbcUrl.replaceAfterLast('/', "legacy_sim"),
                ReconcileConfig.PASSWORD.value to reconcilePassword,
                ReconcileConfig.INTERVAL to "1h",
                ReconcileConfig.RECHECK_AFTER to "3s",
                ReconcileConfig.WAIT_TIMEOUT to "60s",
                "EIA_LOG_FORMAT" to "console",
            )
        }
        lateinit var admin: Admin
        var outputContentId = ContentId(0)

        fun url(database: String = "legacy_sim"): String = postgres.jdbcUrl.replaceAfterLast('/', database)

        fun dataSource(
            user: String,
            password: String,
            database: String = "legacy_sim",
        ) = PGSimpleDataSource().apply {
            setURL(url(database))
            this.user = user
            this.password = password
        }

        // クラスタの全体を見る(ロールと DB の作成・pg_stat_activity)ので、既定の DB に接続する
        fun <T> superuser(block: (Connection) -> T): T =
            dataSource(postgres.username, postgres.password, postgres.databaseName).connection.use(block)

        /** レガシーのアプリとして書く(JST の現地時刻。legacy-sim の simulate と同じ書き方)。 */
        fun legacy(sql: String) =
            dataSource("legacy_sim_app", appPassword).connection.use { c ->
                c.autoCommit = false
                c.createStatement().use { s ->
                    s.execute("SET LOCAL TIME ZONE 'Asia/Tokyo'")
                    s.execute(sql)
                }
                c.commit()
            }

        fun insert(
            number: String,
            status: String = "1",
        ) = legacy(
            "INSERT INTO t_juchu (col_01, col_02, col_03, col_04, col_05, col_06, col_07) " +
                "VALUES (nextval('sq_juchu'), '$number', '$status', '山田商事株式会社', 'C0000101', 1200.00, LOCALTIMESTAMP)",
        )

        fun request(
            method: String,
            uri: String,
            body: String? = null,
        ): HttpResponse<String> =
            http.send(
                HttpRequest
                    .newBuilder(URI.create(uri))
                    .header("Content-Type", "application/json")
                    .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        fun published(waits: ReconcileWaits = ReconcileWaits(60.seconds, 500.milliseconds)) =
            KafkaPublishedLegacyOrders(
                admin = admin,
                consumers = { KafkaConsumer(PublishedReaderSettings.properties(kafka.bootstrapServers)) },
                writerSchemas = WriterSchemas(ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl), registryHttp)),
                waits = waits,
            )

        fun source(waits: ReconcileWaits = ReconcileWaits(60.seconds, 500.milliseconds)) =
            JdbcLegacySource(dataSource("eiaf_reconcile", reconcilePassword), waits = waits)

        /** 照合のロールのセッションのうち、トランザクションを開けたまま(スナップショットを持ったまま)のものの数。 */
        fun openReconcileTransactions(): Long =
            superuser { c ->
                c.createStatement().use { s ->
                    s
                        .executeQuery(
                            "SELECT count(*) FROM pg_stat_activity WHERE usename = 'eiaf_reconcile' " +
                                "AND (state LIKE 'idle in transaction%' OR backend_xmin IS NOT NULL)",
                        ).use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                }
            }

        /**
         * 本番と同じアダプタで照合する。[beforeWait] はレガシーを読んだ後・取り込みの待ちの前に 1 回目だけ呼ぶ(時点のずれを作る)。
         * [pauses] に比べ直しの回数を数える。
         */
        suspend fun reconcile(
            beforeWait: (() -> Unit)? = null,
            pauses: MutableList<kotlin.time.Duration> = mutableListOf(),
            legacySource: LegacySource = source(),
        ): Result<ReconciliationReport, DomainError> {
            var first = true
            val hooked =
                object : LegacySource {
                    override suspend fun read(scope: ReconcileScope): Result<SourceSnapshot, DomainError> = legacySource.read(scope)

                    override suspend fun awaitCaptured(position: String): Result<Unit, DomainError> {
                        // 待ちの間に、照合のトランザクションが残っていない(VACUUM を止めない。ADR-0027)
                        openReconcileTransactions() shouldBe 0
                        if (first) beforeWait?.invoke()
                        first = false
                        return legacySource.awaitCaptured(position)
                    }
                }
            return ReconcileLegacyOrdersService(hooked, published(), Sha256Fingerprints, {
                pauses += it
                delay(it)
            }, 3.seconds)()
        }

        /** 出力に、注文番号の最新の状態(値)が届くまで待つ。 */
        suspend fun awaitPublished(vararg numbers: String) {
            val deadline = TimeSource.Monotonic.markNow() + 90.seconds
            while (true) {
                val latest = published().readCaughtUp(ReconcileScope.Keys(numbers.toSet()))
                if (latest is Result.Ok && latest.value.keys.containsAll(numbers.toList())) return
                check(deadline.hasNotPassedNow()) { "出力に ${numbers.toList()} が届きません: $latest" }
                delay(1_000)
            }
        }

        /** 出力のトピックに、ACL を通さずに直接書く(わざとずれを作る)。[status] が null なら tombstone。 */
        fun inject(
            number: String,
            status: String?,
        ) {
            val schema = Schema.Parser().parse(LegacyOrderEventSchemas.legacyOrderChanged.schema)
            val value =
                status?.let {
                    val record =
                        GenericData.Record(schema).apply {
                            put("orderNumber", number)
                            put("customerCode", "C0000101")
                            put("customerName", "山田商事株式会社")
                            put("status", GenericData.EnumSymbol(schema.getField("status").schema(), it))
                            put(
                                "totalAmount",
                                GenericData.Record(schema.getField("totalAmount").schema()).apply {
                                    put("minorUnits", 1200L)
                                    put("currency", "JPY")
                                },
                            )
                            put("orderedAt", 1_791_417_600_000_000L)
                            put("legacyUpdatedAt", null)
                            put(
                                "source",
                                GenericData.Record(schema.getField("source").schema()).apply {
                                    put("lsn", 1L)
                                    put("committedAt", 1_791_417_600_000_000L)
                                    put("snapshot", false)
                                },
                            )
                        }
                    ByteArrayOutputStream().use { out ->
                        val encoder = EncoderFactory.get().binaryEncoder(out, null)
                        GenericDatumWriter<GenericData.Record>(schema).write(record, encoder)
                        encoder.flush()
                        ApicurioWireFormat.frame(outputContentId, out.toByteArray())
                    }
                }
            KafkaProducer(
                mapOf<String, Any>(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers),
                ByteArraySerializer(),
                ByteArraySerializer(),
            ).use { it.send(ProducerRecord(OUTPUT_TOPIC, number.toByteArray(), value)).get() }
        }

        beforeSpec {
            postgres.start()
            registry.start()
            kafka.start()
            connect.start()
            // ローカル基盤(postgres/init)と同じロール。照合のロールは接続数・問い合わせの時間・トランザクションの放置の上限つき(40-reconcile.sh)
            superuser { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE ROLE legacy_sim LOGIN PASSWORD '$ownerPassword'")
                    s.execute("CREATE ROLE legacy_sim_app LOGIN PASSWORD '$appPassword'")
                    s.execute("CREATE ROLE debezium LOGIN REPLICATION PASSWORD '$cdcPassword'")
                    s.execute("CREATE ROLE eiaf_reconcile LOGIN PASSWORD '$reconcilePassword' CONNECTION LIMIT 4")
                    s.execute("ALTER ROLE eiaf_reconcile SET statement_timeout = '30s'")
                    s.execute("ALTER ROLE eiaf_reconcile SET idle_in_transaction_session_timeout = '60s'")
                    s.execute("ALTER ROLE eiaf_reconcile SET default_transaction_read_only = on")
                    s.execute("CREATE DATABASE legacy_sim OWNER legacy_sim")
                    s.execute("GRANT CONNECT ON DATABASE legacy_sim TO legacy_sim_app, debezium, eiaf_reconcile")
                }
            }
            // legacy-sim のマイグレーション(V1 レガシーの表、V2 CDC の設定、V3 照合の権限)を、所有者で適用する
            val placeholders = mapOf("appRole" to "legacy_sim_app", "cdcRole" to "debezium", "reconcileRole" to "eiaf_reconcile")
            dataSource("legacy_sim", ownerPassword).connection.use { c ->
                listOf("V1__legacy_schema.sql", "V2__dba_cdc_setup.sql", "V3__dba_reconcile_grants.sql").forEach { file ->
                    val sql =
                        placeholders.entries.fold(
                            repositoryRoot.resolve("services/legacy-sim/app/src/main/resources/db/legacy/$file").readText(),
                        ) { text, (name, value) -> text.replace("\${$name}", value) }
                    c.createStatement().use { it.execute(sql) }
                }
            }
            admin = Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers))
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
            admin.createTopics(defined).all().get()
            outputContentId =
                runBlocking {
                    ApicurioRegistryClient(
                        SchemaRegistryConfig(registry.baseUrl),
                        registryHttp,
                    ).register(LegacyOrderEventSchemas.legacyOrderChanged).ok()
                }
            val config =
                Json
                    .parseToJsonElement(
                        KafkaConnectContainer.infraDirectory.resolve("kafka-connect/$CONNECTOR.json").readText(),
                    ).jsonObject
            val put = request("PUT", "${connect.restUrl}/connectors/$CONNECTOR/config", config["config"].toString())
            check(put.statusCode() in 200..201) { "コネクタを登録できません: ${put.statusCode()} ${put.body()}" }
            val deadline = TimeSource.Monotonic.markNow() + 90.seconds
            while (true) {
                val status = Json.parseToJsonElement(request("GET", "${connect.restUrl}/connectors/$CONNECTOR/status").body()).jsonObject
                val running =
                    status["tasks"]?.jsonArray?.let { tasks ->
                        tasks.isNotEmpty() && tasks.all { (it as JsonObject)["state"]?.jsonPrimitive?.content == "RUNNING" }
                    } == true
                if (running) break
                check(deadline.hasNotPassedNow()) { "コネクタが RUNNING になりません: $status" }
                Thread.sleep(1_000)
            }
            // 照合を有効にして起動する(起動の直後に 1 回照合する。間隔は長くし、テストの照合と重ねない)
            acl = AclServer.start(aclEnv).ok()
        }

        afterSpec {
            acl.stop()
            admin.close()
            registryHttp.close()
            connect.stop()
            kafka.stop()
            registry.stop()
            postgres.stop()
            network.close()
        }

        test("レガシーと出力が一致する。変換できない行(DLQ に入る)は既知の差として別に数え、ずれにしない") {
            insert("R000000001")
            insert("R000000002")
            insert("R000000003", status = "7")
            awaitPublished("R000000001", "R000000002")

            val pauses = mutableListOf<kotlin.time.Duration>()
            val report = reconcile(pauses = pauses).ok()

            report.consistent shouldBe true
            report.unconvertible shouldBe setOf("R000000003")
            report.compared shouldBe 3
            report.digest.length shouldBe 64
            pauses.shouldBeEmpty()
            // 手動の照合(サブコマンド)も一致で終わる
            ReconcileCommand.run(aclEnv) {} shouldBe ReconcileCommand.CONSISTENT
        }

        test("比べている最中にレガシーが更新・登録・削除されても、ずれと判定しない(比べ直しで一致する)。待ちの間にトランザクションを残さない") {
            val pauses = mutableListOf<kotlin.time.Duration>()
            val report =
                reconcile(
                    beforeWait = {
                        // レガシーを読んだ後(位置 X の後)の変更。出力には届くが、読んだスナップショットにはない
                        legacy("UPDATE t_juchu SET col_03 = '2', col_08 = LOCALTIMESTAMP WHERE col_02 = 'R000000001'")
                        insert("R000000004")
                        legacy("DELETE FROM t_juchu WHERE col_02 = 'R000000002'")
                    },
                    pauses = pauses,
                ).ok()

            report.drift shouldBe emptyMap()
            // 1 回目の比較では食い違い、比べ直して一致した(時点のずれを実際に通った)
            pauses shouldHaveSize 1
        }

        test("出力にわざと間違った値・tombstone・レガシーにない値を書くと、キーと種類(STALE / MISSING / EXTRA)を特定してずれと判定する") {
            awaitPublished("R000000001", "R000000004")
            inject("R000000001", "CANCELLED")
            inject("R000000004", null)
            inject("Z000000009", "ACCEPTED")

            val report = reconcile().ok()

            report.drift shouldBe
                mapOf("R000000001" to Mismatch.STALE, "R000000004" to Mismatch.MISSING, "Z000000009" to Mismatch.EXTRA)
            report.unconvertible shouldBe setOf("R000000003")
        }

        test("reconcile のサブコマンド(Runbook の手動の照合)は、ずれのキーと種類を出して終了コード 1 で終わる") {
            val lines = mutableListOf<String>()
            ReconcileCommand.run(aclEnv, lines::add) shouldBe ReconcileCommand.DRIFT
            lines.filter { it.startsWith("DRIFT ") || it.startsWith("UNCONVERTIBLE ") } shouldBe
                listOf("DRIFT STALE R000000001", "DRIFT MISSING R000000004", "DRIFT EXTRA Z000000009", "UNCONVERTIBLE R000000003")
            lines.first() shouldStartWith "position="
            ReconcileCommand.run(aclEnv - ReconcileConfig.DB_URL) {} shouldBe ReconcileCommand.USAGE
        }

        test("ACL が止まっていて追いつかなければ、ずれではなく検査の失敗(待ちの上限の超過)") {
            acl.stop()
            insert("R000000005")
            val short = ReconcileWaits(5.seconds, 500.milliseconds)
            val result =
                ReconcileLegacyOrdersService(source(short), published(short), Sha256Fingerprints, { delay(it) }, 1.seconds)()
            result.shouldBeInstanceOf<Result.Err<DomainError>>().error.shouldBeInstanceOf<UnavailableError>()
        }
    })
