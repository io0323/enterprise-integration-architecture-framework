@file:Suppress("MagicNumber") // 件数・待ち時間・ポート

package io.eia.legacysim.app

import io.apicurio.registry.serde.avro.AvroKafkaDeserializer
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.KafkaConnectContainer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldHaveLength
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.Network
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.util.HexFormat
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import java.net.http.HttpClient as JdkHttpClient

private const val TOPIC = "_cdc.legacy.public.t_juchu"
private const val CONNECTOR = "legacy-juchu"
private const val RAW_GROUP = "cdc-raw"

/** レガシーの「未設定」の番兵値 9999-12-31 00:00:00 の MicroTimestamp(現地時刻をそのまま UTC のエポックとみなした値)。 */
private const val UNSET_MICROS = 253_402_214_400_000_000L

/** 生の CDC のトピックの 1 件(キーは受注番号。値は Debezium の Envelope)。 */
private data class RawChange(
    val key: String,
    val op: String,
    val before: GenericRecord?,
    val after: GenericRecord?,
    val snapshot: String,
    val lsn: Long?,
) {
    val orderNumber: String get() = (after ?: before)?.get("col_02").toString()
}

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))

/**
 * legacy-sim(レガシーの受注表)→ Debezium(Kafka Connect)→ 生の CDC のトピックを、ローカル基盤と同じ構成で確かめる(P06 ⑤a。ADR-0026)。
 *
 * - コネクタは infra/local/kafka-connect/legacy-juchu.json をそのまま登録する。Kafka Connect は compose と同じイメージ。
 * - 生のトピックの Avro は Apicurio の公式の Deserializer で読む(Converter が書いた形式をそのまま読めることも確かめる)。
 * - Snapshot(initial)・変更(c / u / d)・削除の変更前の値(REPLICA IDENTITY FULL)・キー(message.key.columns の受注番号)・
 *   signal 表からの Incremental Snapshot・Apicurio のグループ(cdc-raw)・Debezium の権限を確かめる。
 */
class LegacyCdcIT :
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
        val connect = KafkaConnectContainer("kafka:19092", mapOf("DEBEZIUM_DB_PASSWORD" to cdcPassword)).withNetwork(network)
        val http = JdkHttpClient.newHttpClient()

        fun url(database: String = "legacy_sim"): String = postgres.jdbcUrl.replaceAfterLast('/', database)

        fun <T> connect(
            user: String,
            password: String,
            database: String = "legacy_sim",
            block: (Connection) -> T,
        ): T =
            PGSimpleDataSource()
                .apply {
                    setURL(url(database))
                    this.user = user
                    this.password = password
                }.connection
                .use(block)

        fun execute(
            user: String,
            password: String,
            sql: String,
        ) = connect(user, password) { c -> c.createStatement().use { it.execute(sql) } }

        fun strings(
            user: String,
            password: String,
            sql: String,
        ): List<String> =
            connect(user, password) { c ->
                c.createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
            }

        // コンテナの起動の後に URL が決まるため、使うときに作る
        val env by lazy {
            mapOf(
                LegacySimConfig.DB_URL to url(),
                LegacySimConfig.OWNER_PASSWORD.value to ownerPassword,
                LegacySimConfig.APP_PASSWORD.value to appPassword,
            )
        }

        fun simulate(vararg args: String): List<String> {
            val changed = mutableListOf<String>()
            LegacySimCommands.run(listOf("simulate") + args, env, changed::add) shouldBe LegacySimCommands.OK
            return changed
        }

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

        fun deserializer(isKey: Boolean): AvroKafkaDeserializer<GenericRecord> =
            AvroKafkaDeserializer<GenericRecord>().apply { configure(mapOf("apicurio.registry.url" to registry.baseUrl), isKey) }

        /** 生のトピックを最初から読み、[done] が真になるまで([timeout] まで)読んだ変更を返す。 */
        fun readRaw(
            timeout: kotlin.time.Duration = 120.seconds,
            done: (List<RawChange>) -> Boolean,
        ): List<RawChange> {
            val properties =
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "legacy-cdc-it.${Uuid.random()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
            val keys = deserializer(isKey = true)
            val values = deserializer(isKey = false)
            val received = mutableListOf<RawChange>()
            KafkaConsumer(properties, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
                consumer.subscribe(listOf(TOPIC))
                val deadline = TimeSource.Monotonic.markNow() + timeout
                while (!done(received)) {
                    check(deadline.hasNotPassedNow()) { "期待した変更が届きません: ${received.map { it.op to it.orderNumber }}" }
                    consumer.poll(Duration.ofMillis(500)).forEach { record ->
                        // tombstones.on.delete=false のため、値のないレコードは届かない
                        val value = checkNotNull(record.value()) { "値のないレコード(tombstone)が届きました: offset ${record.offset()}" }
                        val key = keys.deserialize(record.topic(), record.headers(), record.key())
                        val envelope = values.deserialize(record.topic(), record.headers(), value)
                        val source = envelope.get("source") as GenericRecord
                        received +=
                            RawChange(
                                key = key.get("col_02").toString(),
                                op = envelope.get("op").toString(),
                                before = envelope.get("before") as GenericRecord?,
                                after = envelope.get("after") as GenericRecord?,
                                snapshot = source.get("snapshot").toString(),
                                lsn = source.get("lsn") as Long?,
                            )
                    }
                }
            }
            return received
        }

        fun awaitRunning() {
            val deadline = TimeSource.Monotonic.markNow() + 90.seconds
            while (true) {
                val status = Json.parseToJsonElement(request("GET", "${connect.restUrl}/connectors/$CONNECTOR/status").body()).jsonObject
                val running =
                    status["connector"]
                        ?.jsonObject
                        ?.get("state")
                        ?.jsonPrimitive
                        ?.content == "RUNNING" &&
                        status["tasks"]?.jsonArray?.let { tasks ->
                            tasks.isNotEmpty() && tasks.all { (it as JsonObject)["state"]?.jsonPrimitive?.content == "RUNNING" }
                        } == true
                if (running) return
                check(deadline.hasNotPassedNow()) { "コネクタが RUNNING になりません: $status" }
                Thread.sleep(1_000)
            }
        }

        lateinit var seeded: List<String>

        beforeSpec {
            postgres.start()
            registry.start()
            kafka.start()
            connect.start()
            // ローカル基盤(infra/local/postgres/init)と同じロールと DB
            connect(postgres.username, postgres.password, postgres.databaseName) { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE ROLE legacy_sim LOGIN PASSWORD '$ownerPassword'")
                    s.execute("CREATE ROLE legacy_sim_app LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION")
                    s.execute("CREATE ROLE debezium LOGIN REPLICATION PASSWORD '$cdcPassword'")
                    s.execute("CREATE ROLE eiaf_reconcile LOGIN PASSWORD '${randomHex()}' CONNECTION LIMIT 4")
                    s.execute("CREATE DATABASE legacy_sim OWNER legacy_sim")
                    s.execute("REVOKE ALL ON DATABASE legacy_sim FROM PUBLIC")
                    s.execute("GRANT CONNECT ON DATABASE legacy_sim TO legacy_sim_app, debezium")
                }
            }
            LegacySimCommands.run(listOf("migrate"), env) {} shouldBe LegacySimCommands.OK
            // コネクタの登録の前からある受注(初回の Snapshot で読む)
            seeded = simulate("seed", "2")

            val file = KafkaConnectContainer.infraDirectory.resolve("kafka-connect/$CONNECTOR.json")
            val config = Json.parseToJsonElement(file.readText()).jsonObject
            config["name"]!!.jsonPrimitive.content shouldBe CONNECTOR
            val put = request("PUT", "${connect.restUrl}/connectors/$CONNECTOR/config", config["config"].toString())
            check(put.statusCode() in 200..201) { "コネクタを登録できません: ${put.statusCode()} ${put.body()}" }
            awaitRunning()
        }

        afterSpec {
            connect.stop()
            kafka.stop()
            registry.stop()
            postgres.stop()
            network.close()
        }

        test("登録の前からある受注は、初回の Snapshot(op=r)で受注番号をキーにして届く。値はレガシーの形式のまま") {
            val changes = readRaw { received -> received.map { it.key }.containsAll(seeded) }
            val snapshot = changes.filter { it.key in seeded }
            snapshot.map { it.op }.toSet() shouldBe setOf("r")
            snapshot.forEach { change ->
                change.key shouldBe change.orderNumber
                change.snapshot shouldBeIn setOf("first", "first_in_data_collection", "true", "last_in_data_collection", "last")
                val after = change.after.shouldNotBeNull()
                // 生のトピックは変換しない: 固定長の末尾の空白・小数の文字列(decimal.handling.mode=string)・状態区分のまま
                after.get("col_04").toString() shouldHaveLength 40
                after.get("col_06").toString() shouldEndWith ".00"
                after.get("col_03").toString() shouldBe "1"
                // タイムゾーンのない時刻は、現地時刻の値をそのまま UTC のエポックとみなしたマイクロ秒(MicroTimestamp)
                (after.get("col_07") as Long).shouldNotBeNull()
                after.get("col_08") shouldBe UNSET_MICROS
            }
        }

        test("登録・更新・削除が c / u / d で届く。キーは受注番号で、削除と更新の変更前の値は全部の列を持つ(REPLICA IDENTITY FULL)") {
            val number = simulate("seed", "1").single()
            // 1 件だけ進める・削除するために、対象の受注以外を出荷済にしておく
            execute("legacy_sim", ownerPassword, "UPDATE t_juchu SET col_03 = '3' WHERE col_02 <> '$number'")
            simulate("advance", "1") shouldBe listOf(number)
            execute("legacy_sim", ownerPassword, "DELETE FROM t_juchu WHERE col_02 = '$number'")

            val changes = readRaw { received -> received.any { it.key == number && it.op == "d" } }.filter { it.key == number }
            changes.map { it.op } shouldContainExactly listOf("c", "u", "d")
            val (_, updated, deleted) = changes
            updated.before
                .shouldNotBeNull()
                .get("col_03")
                .toString() shouldBe "1"
            updated.after
                .shouldNotBeNull()
                .get("col_03")
                .toString() shouldBe "2"
            // 更新では最終更新日時を書く(番兵値ではなくなる)
            ((updated.after.get("col_08") as Long) < UNSET_MICROS) shouldBe true
            deleted.after.shouldBeNull()
            val before = deleted.before.shouldNotBeNull()
            before.get("col_02").toString() shouldBe number
            before.get("col_04").shouldNotBeNull()
            before.get("col_06").shouldNotBeNull()
            changes.map { it.lsn.shouldNotBeNull() }.zipWithNext().forEach { (a, b) -> (a < b) shouldBe true }
        }

        test("変換できない値(ACL で DLQ に送る値)も、生のトピックにはそのまま届く") {
            val garbled = simulate("anomaly", "garbled-name").single()
            val unknown = simulate("anomaly", "unknown-status").single()
            val changes = readRaw { received -> received.map { it.key }.containsAll(listOf(garbled, unknown)) }
            changes
                .first { it.key == garbled }
                .after!!
                .get("col_04")
                .toString()
                .contains('�') shouldBe true
            changes
                .first { it.key == unknown }
                .after!!
                .get("col_03")
                .toString() shouldBe "7"
        }

        test("受注番号(キーの列)を変えると、変更前のキーの削除と、変更後のキーの登録で届く") {
            val number = simulate("seed", "1").single()
            val renumbered = "K" + number.drop(1)
            execute("legacy_sim", ownerPassword, "UPDATE t_juchu SET col_02 = '$renumbered' WHERE col_02 = '$number'")
            val changes =
                readRaw { received -> received.any { it.key == renumbered && it.op != "r" } }
                    .filter { it.key == number || it.key == renumbered }
            changes.map { it.key to it.op } shouldContainExactly listOf(number to "c", number to "d", renumbered to "c")
        }

        test("signal 表に execute-snapshot を書くと、Incremental Snapshot で今の受注が届き直す。Snapshot の印は signal 表に残らない") {
            execute(
                "legacy_sim",
                ownerPassword,
                """
                INSERT INTO eiaf_cdc.debezium_signal (id, type, data)
                VALUES ('it-resync-1', 'execute-snapshot', '{"data-collections": ["public.t_juchu"], "type": "incremental"}')
                """.trimIndent(),
            )
            val current = strings("legacy_sim", ownerPassword, "SELECT col_02 FROM t_juchu")
            // 初回の Snapshot と区別するため、source.snapshot が incremental の変更だけを見る
            val resent =
                readRaw { received -> received.filter { it.snapshot == "incremental" }.map { it.key }.containsAll(current) }
                    .filter { it.snapshot == "incremental" }
            resent.map { it.key } shouldContainAll current
            resent.map { it.op }.toSet() shouldBe setOf("r")
            strings("legacy_sim", ownerPassword, "SELECT id FROM eiaf_cdc.debezium_signal") shouldBe listOf("it-resync-1")
        }

        test("生の CDC のスキーマは Converter が Apicurio のグループ cdc-raw に自動で登録する(ADR-0026)") {
            val listed = request("GET", "${registry.baseUrl}/groups/$RAW_GROUP/artifacts?limit=100")
            listed.statusCode() shouldBe 200
            val ids =
                Json
                    .parseToJsonElement(listed.body())
                    .jsonObject["artifacts"]!!
                    .jsonArray
                    .map { it.jsonObject["artifactId"]!!.jsonPrimitive.content }
            ids shouldContainAll listOf("$TOPIC-key", "$TOPIC-value")
            request("GET", "${registry.baseUrl}/groups/default/artifacts/$TOPIC-value").statusCode() shouldBe 404
        }

        test("Debezium のロールは、受注表の SELECT と signal 表の SELECT / INSERT / DELETE だけを持つ") {
            fun privileges(table: String): List<String> =
                connect(postgres.username, postgres.password) { c ->
                    c.createStatement().use { s ->
                        s
                            .executeQuery(
                                "SELECT privilege_type FROM information_schema.role_table_grants " +
                                    "WHERE grantee = 'debezium' AND table_schema || '.' || table_name = '$table' ORDER BY privilege_type",
                            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                    }
                }
            privileges("public.t_juchu") shouldBe listOf("SELECT")
            privileges("eiaf_cdc.debezium_signal") shouldBe listOf("DELETE", "INSERT", "SELECT")
            connect(postgres.username, postgres.password) { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT rolsuper, rolreplication FROM pg_roles WHERE rolname = 'debezium'").use { rs ->
                        rs.next()
                        rs.getBoolean(1) shouldBe false
                        rs.getBoolean(2) shouldBe true
                    }
                }
            }
        }

        test("レガシーの表の定義は V1 のまま(DBA の設定は REPLICA IDENTITY と権限・publication だけ)") {
            connect("legacy_sim", ownerPassword) { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT relreplident FROM pg_class WHERE oid = 'public.t_juchu'::regclass").use { rs ->
                        rs.next()
                        rs.getString(1) shouldBe "f"
                    }
                }
            }
            strings(
                "legacy_sim",
                ownerPassword,
                "SELECT schemaname || '.' || tablename FROM pg_publication_tables WHERE pubname = 'eiaf_legacy' ORDER BY 1",
            ) shouldBe listOf("eiaf_cdc.debezium_signal", "public.t_juchu")
            seeded shouldHaveSize 2
        }
    })
