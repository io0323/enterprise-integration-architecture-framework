@file:Suppress("MagicNumber") // 件数・数量・待ち時間・ポート

package io.eia.inventory.app

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import io.eia.inventory.adapters.inbound.InventoryCommandHandlers
import io.eia.inventory.adapters.inbound.ReleaseStockV1
import io.eia.inventory.adapters.inbound.ReserveStockV1
import io.eia.inventory.adapters.inbound.StockLineV1
import io.eia.inventory.adapters.out.outbox.InventoryEventSchemas
import io.eia.inventory.adapters.out.outbox.StockRejectionReasonV1
import io.eia.inventory.adapters.out.outbox.StockReleaseOutcomeV1
import io.eia.inventory.adapters.out.outbox.StockReleasedV1
import io.eia.inventory.adapters.out.outbox.StockReservationRejectedV1
import io.eia.inventory.adapters.out.outbox.StockReservedV1
import io.eia.inventory.adapters.out.persistence.InventorySchema
import io.eia.platform.grpc.CertificateFailureReason
import io.eia.platform.grpc.GrpcCallRejected
import io.eia.platform.grpc.GrpcCallUnavailable
import io.eia.platform.grpc.GrpcCertificateFailure
import io.eia.platform.grpc.GrpcChannels
import io.eia.platform.grpc.GrpcClient
import io.eia.platform.grpc.GrpcTelemetry
import io.eia.platform.grpc.GrpcTokenSource
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockRequest
import io.eia.platform.grpcstubs.inventory.v1.BatchGetStockResponse
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpc
import io.eia.platform.grpcstubs.inventory.v1.StockQueryServiceGrpcKt.StockQueryServiceCoroutineStub
import io.eia.platform.messagingkafka.AvroEventDeserializer
import io.eia.platform.messagingkafka.AvroEventSerializer
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.EventMetadata
import io.eia.platform.messagingkafka.EventProducer
import io.eia.platform.messagingkafka.EventTopic
import io.eia.platform.messagingkafka.KafkaProducerSettings
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.observability.context.withSpan
import io.eia.platform.schemaregistry.ApicurioRegistryClient
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.schemaregistry.SchemaRegistryConfig
import io.eia.platform.schemaregistry.SchemaSubject
import io.eia.platform.schemaregistry.WriterSchemas
import io.eia.platform.security.tls.ServerTls
import io.eia.platform.security.tls.TlsFiles
import io.eia.platform.security.tls.TlsVariables
import io.eia.platform.testsupport.ApicurioRegistryContainer
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.KafkaBrokerContainer
import io.eia.platform.testsupport.KafkaConnectContainer
import io.eia.platform.testsupport.TestPki
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.sdk.http.token.AccessToken
import io.grpc.Grpc
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.TlsChannelCredentials
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.Network
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Date
import java.util.HexFormat
import kotlin.io.path.readText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import java.net.http.HttpClient as JdkHttpClient

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val CONNECTOR = "inventory-outbox"
private const val ISSUER = "http://issuer.test/realms/eiaf"
private const val OWNER_ROLE = "inventory_service"
private const val DATABASE = "inventory_service"

private fun randomHex(): String = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })

/**
 * inventory-service の全体を、ローカル基盤と同じ構成で確かめる(P07 ④a。ADR-0028・ADR-0029)。
 *
 * コマンド(Kafka)→ inventory(EventConsumer・冪等消費・引当)→ Outbox → Debezium(infra/local/kafka-connect/inventory-outbox.json をそのまま登録)
 * → 返事のトピック。ロールはローカル基盤と同じ(所有者・アプリ・Debezium。Debezium の DB への CONNECT はマイグレーションが付ける)。
 */
class InventoryAppIT :
    FunSpec({
        val network = Network.newNetwork()
        val postgres =
            PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical")
                .withNetwork(network)
                .withNetworkAliases("postgres")
        val registry = ApicurioRegistryContainer()
        val kafka =
            KafkaBrokerContainer()
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
        val pki = TestPki()
        val grpcServerCert = pki.issue("inventory-service", listOf("localhost"))
        val tokenKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
        val jwks =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/jwks") { exchange ->
                    val body = JWKSet(tokenKey.toPublicJWK()).toString().toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }

        fun token(scope: String): String {
            val now = Clock.System.now()
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(ISSUER)
                    .audience("inventory-grpc")
                    .subject("service-account-bff")
                    .claim("azp", "eiaf-bff-graphql")
                    .claim("scope", scope)
                    .issueTime(Date(now.toEpochMilliseconds()))
                    .expirationTime(Date((now + 5.minutes).toEpochMilliseconds()))
                    .build()
            val header =
                JWSHeader
                    .Builder(JWSAlgorithm.RS256)
                    .keyID(tokenKey.keyID)
                    .type(JOSEObjectType.JWT)
                    .build()
            return SignedJWT(header, claims).apply { sign(RSASSASigner(tokenKey)) }.serialize()
        }
        val ktor = HttpClient(CIO)
        val runtime = Observability.init(ObservabilityConfig.of("inventory-it").ok(), TelemetrySinks(), installLogAppender = false)
        lateinit var server: InventoryServer
        lateinit var client: ApicurioRegistryClient
        lateinit var producer: KafkaProducer<ByteArray, ByteArray>
        lateinit var reserveSerializer: AvroEventSerializer<ReserveStockV1>
        lateinit var releaseSerializer: AvroEventSerializer<ReleaseStockV1>

        fun url(): String = postgres.jdbcUrl.replaceAfterLast('/', DATABASE)

        fun connectRequest(
            method: String,
            path: String,
            body: String? = null,
        ): HttpResponse<String> =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("${connect.restUrl}$path"))
                    .header("Content-Type", "application/json")
                    .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        fun health(): Int =
            http
                .send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.healthPort}/health/ready")).build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()

        fun reservedOf(sku: String): Long =
            PGSimpleDataSource()
                .apply {
                    setURL(url())
                    user = postgres.username
                    password = postgres.password
                }.connection
                .use { c ->
                    c.createStatement().use { s ->
                        s.executeQuery("SELECT reserved FROM stock WHERE sku = '$sku'").use { rows ->
                            rows.next()
                            rows.getLong(1)
                        }
                    }
                }

        /** [topic] を最初から読み、キーが [sagaId] のものを [count] 件そろうまで([timeout] まで)読む。 */
        fun <T> replies(
            topic: EventTopic,
            serializer: KSerializer<T>,
            sagaId: String,
            count: Int = 1,
            timeout: kotlin.time.Duration = 90.seconds,
        ): List<Pair<ConsumerRecord<String, ByteArray>, T>> {
            val properties =
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "inventory-it.${Uuid.random()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
            val deserializer = AvroEventDeserializer(serializer, WriterSchemas(client))
            val received = mutableListOf<Pair<ConsumerRecord<String, ByteArray>, T>>()
            KafkaConsumer(properties, StringDeserializer(), ByteArrayDeserializer()).use { consumer ->
                consumer.subscribe(listOf(topic.name))
                val deadline = TimeSource.Monotonic.markNow() + timeout
                while (received.size < count && deadline.hasNotPassedNow()) {
                    consumer.poll(Duration.ofMillis(500)).filter { it.key() == sagaId }.forEach { record ->
                        val value = kotlinx.coroutines.runBlocking { deserializer.deserialize(record.value()) }.ok()
                        received += record to value
                    }
                }
            }
            if (received.size < count) fail("$topic に $sagaId の返事が $count 件届きません(${received.size} 件)")
            return received
        }

        suspend fun reserve(
            sagaId: String,
            vararg lines: Pair<String, Long>,
        ) = EventProducer(producer, runtime, "/sales/order-service")
            .send(
                reserveSerializer,
                sagaId,
                ReserveStockV1(sagaId, "order-$sagaId", lines.mapIndexed { i, (sku, q) -> StockLineV1(i + 1, sku, q) }),
            ).ok()

        suspend fun release(sagaId: String) =
            EventProducer(
                producer,
                runtime,
                "/sales/order-service",
            ).send(releaseSerializer, sagaId, ReleaseStockV1(sagaId, "order-$sagaId")).ok()

        suspend fun queryStock(
            channel: ManagedChannel,
            scope: String,
        ): Result<BatchGetStockResponse, DomainError> {
            val tokens =
                object : GrpcTokenSource {
                    override suspend fun token(): Result<AccessToken, DomainError> =
                        ok(AccessToken(token(scope), Clock.System.now() + 5.minutes, setOf(scope)))

                    override suspend fun invalidate(token: AccessToken) = Unit
                }
            val telemetry = GrpcTelemetry(runtime.openTelemetry, runtime.tracer, runtime.meter)
            val request =
                BatchGetStockRequest
                    .newBuilder()
                    .addSkus("SKU-1")
                    .addSkus("SKU-NOT-IN-STOCK")
                    .build()
            return GrpcClient("inventory", tokens, telemetry, fallbackDeadline = 30.seconds)
                .call(StockQueryServiceCoroutineStub(channel), StockQueryServiceGrpc.getBatchGetStockMethod()) { metadata ->
                    batchGetStock(request, metadata)
                }.also { channel.shutdownNow() }
        }

        fun channel(cert: TestPki.Issued): ManagedChannel =
            GrpcChannels.mutualTls(
                "localhost:${server.grpcPort}",
                ServerTls
                    .load(
                        TlsFiles(TlsVariables("TEST"), cert.certFile.toString(), cert.keyFile.toString(), pki.caFile.toString()),
                    ).ok(),
            )

        beforeSpec {
            postgres.start()
            registry.start()
            kafka.start()
            connect.start()
            // ローカル基盤(infra/local/postgres/init)と同じロール。Debezium の CONNECT はマイグレーションが付ける(ここでは付けない)
            PGSimpleDataSource()
                .apply {
                    setURL(postgres.jdbcUrl)
                    user = postgres.username
                    password = postgres.password
                }.connection
                .use { c ->
                    c.createStatement().use { s ->
                        s.execute("CREATE ROLE $OWNER_ROLE LOGIN PASSWORD '$ownerPassword'")
                        s.execute(
                            "CREATE ROLE ${InventorySchema.APP_ROLE} LOGIN PASSWORD '$appPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE",
                        )
                        s.execute("CREATE ROLE ${InventorySchema.CDC_ROLE} LOGIN REPLICATION PASSWORD '$cdcPassword'")
                        s.execute("CREATE DATABASE $DATABASE OWNER $OWNER_ROLE")
                        s.execute("REVOKE ALL ON DATABASE $DATABASE FROM PUBLIC")
                        s.execute("GRANT CONNECT ON DATABASE $DATABASE TO ${InventorySchema.APP_ROLE}")
                    }
                }
            InventoryCommands.run(listOf("migrate"), mapOf("INVENTORY_DB_URL" to url(), "INVENTORY_DB_PASSWORD" to ownerPassword)) shouldBe
                InventoryCommands.OK

            // 契約の登録(make schemas と同じ)。コマンドは order が送るので、ここではテストが送るために使う
            client = ApicurioRegistryClient(SchemaRegistryConfig(registry.baseUrl, requestTimeout = 30.seconds), ktor)
            val contracts =
                KafkaConnectContainer.infraDirectory.parent.parent
                    .resolve("contracts/avro/inventory")
            val reserveSubject = SchemaSubject(InventoryCommandHandlers.RESERVE.name, contracts.resolve("ReserveStock.avsc").readText())
            val releaseSubject = SchemaSubject(InventoryCommandHandlers.RELEASE.name, contracts.resolve("ReleaseStock.avsc").readText())
            (InventoryEventSchemas.subjects + reserveSubject + releaseSubject).forEach { client.register(it).ok() }
            val ids = SchemaIdBook(listOf(reserveSubject, releaseSubject), client).also { it.resolve().ok() }
            reserveSerializer = AvroEventSerializer(InventoryCommandHandlers.RESERVE, reserveSubject, ReserveStockV1.serializer(), ids)
            releaseSerializer = AvroEventSerializer(InventoryCommandHandlers.RELEASE, releaseSubject, ReleaseStockV1.serializer(), ids)

            // コマンドのトピック(本来は order の Outbox のコネクタが作る)と DLQ(infra/local/kafka/topics.conf)
            Admin.create(mapOf("bootstrap.servers" to kafka.bootstrapServers)).use { admin ->
                val topics = listOf(InventoryCommandHandlers.RESERVE.name, InventoryCommandHandlers.RELEASE.name)
                admin
                    .createTopics(
                        topics.flatMap { listOf(NewTopic(it, 3, 1), NewTopic(DeadLetterPublisher.deadLetterTopic(it), 3, 1)) },
                    ).all()
                    .get()
            }
            producer = KafkaProducer(KafkaProducerSettings(kafka.bootstrapServers, "inventory-it").toProperties())

            val config =
                Json
                    .parseToJsonElement(
                        KafkaConnectContainer.infraDirectory.resolve("kafka-connect/$CONNECTOR.json").readText(),
                    ).jsonObject
            config["name"]!!.jsonPrimitive.content shouldBe CONNECTOR
            val put = connectRequest("PUT", "/connectors/$CONNECTOR/config", config["config"].toString())
            check(put.statusCode() in 200..201) { "コネクタを登録できません: ${put.statusCode()} ${put.body()}" }
            val deadline = TimeSource.Monotonic.markNow() + 90.seconds
            while (true) {
                val status = Json.parseToJsonElement(connectRequest("GET", "/connectors/$CONNECTOR/status").body()).jsonObject
                val running =
                    status["connector"]
                        ?.jsonObject
                        ?.get("state")
                        ?.jsonPrimitive
                        ?.content == "RUNNING" &&
                        status["tasks"]?.jsonArray?.let { tasks ->
                            tasks.isNotEmpty() && tasks.all { (it as JsonObject)["state"]?.jsonPrimitive?.content == "RUNNING" }
                        } == true
                if (running) break
                check(deadline.hasNotPassedNow()) { "コネクタが RUNNING になりません: $status\n${connect.diagnostics(CONNECTOR)}" }
                Thread.sleep(1_000)
            }

            server =
                InventoryServer
                    .start(
                        mapOf(
                            "INVENTORY_DB_URL" to url(),
                            "INVENTORY_APP_DB_PASSWORD" to appPassword,
                            "INVENTORY_KAFKA_BOOTSTRAP" to kafka.bootstrapServers,
                            "INVENTORY_SCHEMA_REGISTRY_URL" to registry.baseUrl,
                            "INVENTORY_HEALTH_PORT" to "0",
                            "INVENTORY_GRPC_PORT" to "0",
                            "INVENTORY_GRPC_TLS_CERT_FILE" to grpcServerCert.certFile.toString(),
                            "INVENTORY_GRPC_TLS_KEY_FILE" to grpcServerCert.keyFile.toString(),
                            "INVENTORY_GRPC_TLS_CLIENT_CA_FILE" to pki.caFile.toString(),
                            "INVENTORY_GRPC_TLS_ALLOWED_CLIENTS" to "bff-graphql",
                            "OIDC_ISSUER" to ISSUER,
                            "OIDC_JWKS_URI" to "http://127.0.0.1:${jwks.address.port}/jwks",
                        ),
                    ).ok()
            val ready = TimeSource.Monotonic.markNow() + 60.seconds
            while (health() != 200) {
                check(ready.hasNotPassedNow()) { "inventory-service が ready になりません" }
                Thread.sleep(500)
            }
        }

        afterSpec {
            server.stop()
            jwks.stop(0)
            pki.close()
            producer.close()
            ktor.close()
            runtime.close()
            connect.stop()
            kafka.stop()
            registry.stop()
            postgres.stop()
            network.close()
        }

        context("在庫の照会の gRPC(INT-INVENTORY-003。ROADMAP P13 の DoD。ADR-0042)") {
            test("契約から生成したスタブで、許可されたクライアントが在庫を読める。ない SKU は分けて返す") {
                val response = queryStock(channel(pki.issue("bff", listOf("bff-graphql"))), "inventory.stock:read").ok()

                response.levelsList.map { it.sku } shouldBe listOf("SKU-1")
                response.unknownSkusList shouldBe listOf("SKU-NOT-IN-STOCK")
            }

            test("許可の一覧にないクライアント証明書は PERMISSION_DENIED") {
                queryStock(channel(pki.issue("other", listOf("someone-else"))), "inventory.stock:read") shouldBe
                    err(GrpcCallRejected("inventory", Status.Code.PERMISSION_DENIED))
            }

            test("スコープが足りないトークンは PERMISSION_DENIED") {
                queryStock(channel(pki.issue("bff", listOf("bff-graphql"))), "sales.order:read") shouldBe
                    err(GrpcCallRejected("inventory", Status.Code.PERMISSION_DENIED))
            }

            test("mTLS なしの接続を拒否する") {
                val anonymous =
                    Grpc
                        .newChannelBuilder(
                            "localhost:${server.grpcPort}",
                            TlsChannelCredentials.newBuilder().trustManager(pki.caFile.toFile()).build(),
                        ).build()
                queryStock(anonymous, "inventory.stock:read") shouldBe
                    err(GrpcCertificateFailure("inventory", CertificateFailureReason.REJECTED_BY_PEER))

                val plaintext = Grpc.newChannelBuilder("localhost:${server.grpcPort}", InsecureChannelCredentials.create()).build()
                queryStock(plaintext, "inventory.stock:read") shouldBe err(GrpcCallUnavailable("inventory", Status.Code.UNAVAILABLE))
            }
        }

        test("引当の指示に、Saga ID のキー・CloudEvents のヘッダ・同じトレースの返事(inventory.stock.reserved.v1)が Outbox と Debezium で届く") {
            val sagaId = "saga-${Uuid.random()}"
            val sent = runtime.withSpan("test order saga") { reserve(sagaId, "SKU-1" to 2L) }

            val (record, value) = replies(InventoryEventSchemas.STOCK_RESERVED, StockReservedV1.serializer(), sagaId).single()
            value shouldBe StockReservedV1(sagaId, "order-$sagaId")
            val metadata = EventMetadata.fromHeaders(record.headers()).ok()
            metadata.type shouldBe "inventory.stock.reserved"
            metadata.source shouldBe "/inventory/inventory-service"
            // 返事は、コマンドと同じトレース・同じ Correlation ID でつながる(ADR-0028 §4)
            metadata.traceParent.traceId shouldBe sent.metadata.traceParent.traceId
            metadata.correlationId shouldBe sent.metadata.correlationId
            reservedOf("SKU-1") shouldBe 2
        }

        test("在庫がなければ、inventory.stock.reservation-rejected.v1(INSUFFICIENT_STOCK)。在庫は変えない") {
            val sagaId = "saga-${Uuid.random()}"
            reserve(sagaId, "SKU-SOLDOUT" to 1L)

            replies(
                InventoryEventSchemas.STOCK_RESERVATION_REJECTED,
                StockReservationRejectedV1.serializer(),
                sagaId,
            ).single().second shouldBe
                StockReservationRejectedV1(sagaId, "order-$sagaId", StockRejectionReasonV1.INSUFFICIENT_STOCK)
            reservedOf("SKU-SOLDOUT") shouldBe 0
        }

        test("解放が先に届くと NOT_RESERVED を返し、後から届いた引当の指示は ALREADY_RELEASED で拒否する(ADR-0029 §5)") {
            val sagaId = "saga-${Uuid.random()}"
            release(sagaId)
            replies(InventoryEventSchemas.STOCK_RELEASED, StockReleasedV1.serializer(), sagaId).single().second.outcome shouldBe
                StockReleaseOutcomeV1.NOT_RESERVED

            reserve(sagaId, "SKU-2" to 1L)
            replies(
                InventoryEventSchemas.STOCK_RESERVATION_REJECTED,
                StockReservationRejectedV1.serializer(),
                sagaId,
            ).single().second.reason shouldBe
                StockRejectionReasonV1.ALREADY_RELEASED
            reservedOf("SKU-2") shouldBe 0
        }

        test("同じメッセージ(同じ ce_id)が 2 回届いても、返事は 1 回だけ") {
            val sagaId = "saga-${Uuid.random()}"
            val sent = reserve(sagaId, "SKU-3" to 1L)
            // 届いたレコードをそのまま(ヘッダとペイロードを同じにして)もう一度送る
            val properties =
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "inventory-it.${Uuid.random()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
            val original =
                KafkaConsumer(properties, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
                    consumer.subscribe(listOf(InventoryCommandHandlers.RESERVE.name))
                    val deadline = TimeSource.Monotonic.markNow() + 30.seconds
                    var found: ConsumerRecord<ByteArray, ByteArray>? = null
                    while (found == null && deadline.hasNotPassedNow()) {
                        found = consumer.poll(Duration.ofMillis(500)).firstOrNull { String(it.key()) == sagaId }
                    }
                    found ?: fail("送った引当の指示が見つかりません")
                }
            producer.send(ProducerRecord(original.topic(), null, original.key(), original.value(), original.headers())).get()

            replies(InventoryEventSchemas.STOCK_RESERVED, StockReservedV1.serializer(), sagaId).size shouldBe 1
            // 2 件目が来ないことを、少し待ってから確かめる
            Thread.sleep(5_000)
            replies(InventoryEventSchemas.STOCK_RESERVED, StockReservedV1.serializer(), sagaId, timeout = 5.seconds).size shouldBe 1
            sent.metadata.id shouldBe EventMetadata.fromHeaders(original.headers()).ok().id
            reservedOf("SKU-3") shouldBe 1
        }

        test("Poison Message(Avro として読めないコマンド)は DLQ に隔離され、後続のコマンドは処理される") {
            val sagaId = "saga-${Uuid.random()}"
            val poison =
                ProducerRecord<ByteArray, ByteArray>(InventoryCommandHandlers.RESERVE.name, sagaId.toByteArray(), byteArrayOf(1, 2, 3))
            EventMetadata(
                Uuid.random(),
                "/sales/order-service",
                InventoryCommandHandlers.RESERVE.ceType,
                kotlin.time.Clock.System
                    .now(),
                io.eia.shared.resilience.trace.TraceParent
                    .parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                    .ok(),
                io.eia.shared.kernel.CorrelationId
                    .generate(),
            ).toHeaders().forEach { poison.headers().add(it) }
            producer.send(poison).get()
            reserve(sagaId, "SKU-1" to 1L)

            replies(InventoryEventSchemas.STOCK_RESERVED, StockReservedV1.serializer(), sagaId).size shouldBe 1
            val properties =
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "inventory-it.${Uuid.random()}",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
            KafkaConsumer(properties, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
                consumer.subscribe(listOf(DeadLetterPublisher.deadLetterTopic(InventoryCommandHandlers.RESERVE.name)))
                val deadline = TimeSource.Monotonic.markNow() + 30.seconds
                var reason: String? = null
                while (reason == null && deadline.hasNotPassedNow()) {
                    reason =
                        consumer
                            .poll(Duration.ofMillis(500))
                            .firstOrNull { String(it.key()) == sagaId }
                            ?.headers()
                            ?.lastHeader("eiaf.dlq.reason")
                            ?.value()
                            ?.let(::String)
                }
                reason shouldBe "UNDECODABLE"
            }
            health() shouldBe 200
        }
    })
