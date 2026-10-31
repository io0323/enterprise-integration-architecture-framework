@file:Suppress("MagicNumber") // テストデータの件数・連番

package io.eia.fileexchange.adapters

import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import io.eia.fileexchange.adapters.file.InboxPrefixes
import io.eia.fileexchange.adapters.file.S3Inbox
import io.eia.fileexchange.adapters.file.SalesDailyLayout
import io.eia.fileexchange.adapters.partner.OwnPgpKey
import io.eia.fileexchange.adapters.partner.PartnerRegistry
import io.eia.fileexchange.adapters.partner.SftpPartnerOutbox
import io.eia.fileexchange.adapters.persistence.FileExchangeSchema
import io.eia.fileexchange.adapters.persistence.JdbcDeliveries
import io.eia.fileexchange.adapters.persistence.JdbcReceivedFiles
import io.eia.fileexchange.application.port.inbound.PartnerDelivery
import io.eia.fileexchange.application.usecase.DeliverFileService
import io.eia.fileexchange.application.usecase.ReceiveFileService
import io.eia.fileexchange.domain.Delivery
import io.eia.fileexchange.domain.DeliveryOutcome
import io.eia.fileexchange.domain.DeliveryState
import io.eia.fileexchange.domain.PartnerId
import io.eia.platform.filetransfer.RemoteUnavailable
import io.eia.platform.filetransfer.layout.FileFormat
import io.eia.platform.filetransfer.layout.FileName
import io.eia.platform.filetransfer.pgp.PgpFiles
import io.eia.platform.filetransfer.pgp.PgpKeys
import io.eia.platform.filetransfer.pgp.PgpVerification
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.filetransfer.store.S3StoreConfig
import io.eia.platform.filetransfer.verify.FileVerifier
import io.eia.platform.filetransfer.write.FilePublisher
import io.eia.platform.filetransfer.write.PublishRequest
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.InfraImages
import io.eia.platform.testsupport.SeaweedFsContainer
import io.eia.platform.testsupport.SftpServerContainer
import io.eia.platform.testsupport.TestKeys
import io.eia.platform.testsupport.TestLedgers
import io.eia.shared.kernel.Jitter
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.resilience.CircuitBreakerConfig
import io.eia.shared.resilience.CircuitOpen
import io.eia.shared.resilience.CircuitState
import io.eia.shared.resilience.ResilienceConfig
import io.eia.shared.resilience.SlidingWindow
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlinx.coroutines.delay
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.Network
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.toxiproxy.ToxiproxyContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.nio.file.Files
import java.security.SecureRandom
import java.time.LocalDate
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private const val SFTP_ALIAS = "sftp-b2b"
private const val SFTP_PORT = 2222

/** ToxiproxyContainer が公開するプロキシ用のポート(8666〜8697)の 1 つ。 */
private const val PROXY_PORT = 8666
private const val OUTBOX = "/config/outbox"
private val PARTNER = PartnerId("partner01")

/**
 * テストの実行時間を抑えるため、Circuit Breaker の窓・Open の期間を短くする。
 * 窓は直近 4 件、4 件そろったら判定し、失敗率 50% 以上で Open。Open は 2 秒。Half-Open で 1 件試す。リトライは初回を含めて 3 回、50ms から。
 */
private val CIRCUIT_BREAKER =
    CircuitBreakerConfig(window = SlidingWindow.Count(4), minimumCalls = 4, openDuration = 2.seconds, halfOpenPermits = 1)
private val PARTNER_RESILIENCE =
    ResilienceConfig(
        attemptTimeout = 20.seconds,
        deadline = 60.seconds,
        retry = RetryPolicy(initialDelay = 50.milliseconds, maxAttempts = 3, jitter = Jitter.NONE),
        retryBudget = null,
        circuitBreaker = CIRCUIT_BREAKER,
    )

/**
 * 取引先への配送(ADR-0032 §8)を、実際の PostgreSQL・S3 互換ストレージ(SeaweedFS)・SFTP のサーバ(取引先の代わりの `sftp-b2b`)で確かめる。
 * SFTP のサーバの前に Toxiproxy を置き、取引先の不調で、リトライと Circuit Breaker の遷移がメトリクスに出ることを確かめる(#8-c)。
 *
 * 構成: SftpPartnerOutbox → Toxiproxy → SFTP のサーバ(同じ Docker のネットワーク)。known_hosts は Toxiproxy の宛先で登録する。
 */
class DeliveryIT :
    FunSpec({
        val network = Network.newNetwork()
        val postgres = PostgreSQLContainer(InfraImages.get("POSTGRES_IMAGE").asCompatibleSubstituteFor("postgres"))
        val bucket = "eiaf-file-exchange"
        val prefixes = InboxPrefixes("inbox/sales-daily/", "archive/sales-daily/", "quarantine/sales-daily/")
        val seaweed =
            SeaweedFsContainer(
                mapOf(
                    "eiaf" to listOf("Admin", "Read", "Write", "List"),
                    "eiaf-file-batch-etl" to listOf("Write:$bucket/${prefixes.inbox}*"),
                    "eiaf-file-exchange" to listOf("Read:$bucket", "List:$bucket", "Write:$bucket"),
                ),
            )
        val client = TestKeys.ssh()
        val sftp = SftpServerContainer("partner01", client.publicKey).withNetwork(network).withNetworkAliases(SFTP_ALIAS)
        val toxiproxy = ToxiproxyContainer(InfraImages.get("TOXIPROXY_IMAGE")).withNetwork(network)
        val ours = TestKeys.pgp("EIAF <eiaf@example.com>")
        val partnerPgp = TestKeys.pgp("Partner <partner@example.com>")
        // 取引先の側: 受け取ったファイルを自分の鍵で復号し、EIAF の公開鍵で署名を確かめる
        val receiving = PgpKeys.parse(Secret(ours.publicKey), Secret(partnerPgp.secretKey), Secret(partnerPgp.passphrase)).ok()
        val workDir = Files.createTempDirectory("delivery-it")
        val ownerPassword = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
        val appPassword = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
        val sequence = AtomicInteger()
        val reader = InMemoryMetricReader.create()
        val resilienceMetrics =
            ResilienceMetrics(
                SdkMeterProvider
                    .builder()
                    .registerMetricReader(reader)
                    .build()
                    .get("it"),
            )
        // 依存先ごとに 1 つを作って使い回す(app の配線と同じく ResilienceMetrics から作る)
        val resilience = resilienceMetrics.resilience("sftp-$PARTNER", PARTNER_RESILIENCE)
        val breaker = checkNotNull(resilience.circuitBreaker)
        lateinit var app: PGSimpleDataSource
        lateinit var admin: S3Client
        lateinit var proxy: Proxy
        lateinit var deliver: DeliverFileService

        fun dataSource(
            database: String,
            user: String,
            password: String,
        ) = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replaceAfterLast('/', database))
            this.user = user
            this.password = password
        }

        fun store(identity: String): S3ObjectStore {
            val credentials = seaweed.credentials.getValue(identity)
            return S3ObjectStore(
                S3StoreConfig(URI(seaweed.endpoint), bucket, SecretName("A"), SecretName("S")),
                EnvSecretProvider(mapOf("A" to credentials.accessKey, "S" to credentials.secretKey)),
            )
        }

        val exchange by lazy { store("eiaf-file-exchange") }
        val files by lazy { JdbcReceivedFiles(app) }
        val deliveries by lazy { JdbcDeliveries(app) }
        val receive by lazy {
            ReceiveFileService(S3Inbox(exchange, FileVerifier(exchange, listOf(SalesDailyLayout.V1), workDir), prefixes), files)
        }

        /** 送り手として売上ファイルを受け口に置き、受け取って archive に収める。名前を返す。 */
        suspend fun received(): String {
            val name =
                FileName.of(SalesDailyLayout.V1, Instant.parse("2026-10-14T15:00:00Z"), sequence.incrementAndGet(), FileFormat.PARQUET).ok()
            store("eiaf-file-batch-etl").use { writer ->
                FilePublisher(writer, workDir)
                    .publish(
                        PublishRequest(
                            prefixes.inbox,
                            SalesDailyLayout.V1,
                            name,
                            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                            "corr",
                            Instant.parse("2026-10-14T16:00:00Z"),
                        ),
                        (1..5).asSequence().map {
                            listOf(
                                LocalDate.of(2026, 10, 14),
                                "order-%04d".format(it),
                                java.time.Instant
                                    .parse("2026-10-14T01:00:00Z")
                                    .plusSeconds(it.toLong()),
                                "PLACED",
                                100L * it,
                                "JPY",
                                "JP",
                                "JP-13",
                            )
                        },
                    ).ok()
            }
            receive(name.base).ok()
            return name.base
        }

        /** 取引先の側に届いたファイルを、署名を確かめて復号し、archive のデータファイルと同じ中身かを返す。 */
        fun deliveredIntact(name: String): Boolean {
            val received = workDir.resolve("$name.received.pgp")
            Files.write(received, sftp.readFile("$OUTBOX/$name.parquet.pgp") ?: return false)
            val decrypted = workDir.resolve("$name.decrypted")
            PgpFiles.decryptAndVerify(received, decrypted, receiving).ok() shouldBe
                PgpVerification.Verified(receiving.partnerFingerprint, "$name.parquet")
            val archived = workDir.resolve("$name.archived")
            exchange.download("${prefixes.archive}$name.parquet", archived).ok() shouldBe true
            return Files.readAllBytes(decrypted).contentEquals(Files.readAllBytes(archived))
        }

        suspend fun row(name: String): Pair<String, Boolean> =
            deliveries.find(name, PARTNER).ok()?.let { it.state.name to it.alreadyPresent } ?: fail("配送の記録がありません")

        fun counter(
            metric: String,
            filter: (Map<String, Any>) -> Boolean = { true },
        ): Long =
            reader
                .collectAllMetrics()
                .filter { it.name == metric }
                .flatMap { it.longSumData.points }
                .filter { point ->
                    point.attributes
                        .asMap()
                        .mapKeys { it.key.key }
                        .let(filter)
                }.sumOf { it.value }

        fun transitions(
            from: String,
            to: String,
        ) = counter("eia.resilience.circuit_breaker.transitions") {
            it["eia.dependency.name"] == "sftp-$PARTNER" && it["from"] == from && it["to"] == to
        }

        beforeSpec {
            postgres.start()
            seaweed.start()
            sftp.start()
            toxiproxy.start()
            proxy =
                ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort).createProxy("sftp", "0.0.0.0:$PROXY_PORT", "$SFTP_ALIAS:$SFTP_PORT")

            val superuser = dataSource(postgres.databaseName, postgres.username, postgres.password)
            superuser.connection.use { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE ROLE file_exchange LOGIN PASSWORD '$ownerPassword'")
                    s.execute("CREATE ROLE ${FileExchangeSchema.APP_ROLE} LOGIN PASSWORD '$appPassword'")
                    s.execute("CREATE DATABASE file_exchange OWNER file_exchange")
                }
            }
            FileExchangeSchema.migrate(dataSource("file_exchange", "file_exchange", ownerPassword)).ok()
            app = dataSource("file_exchange", FileExchangeSchema.APP_ROLE, appPassword)
            val credentials = seaweed.credentials.getValue("eiaf")
            admin =
                S3Client
                    .builder()
                    .endpointOverride(URI(seaweed.endpoint))
                    .region(Region.US_EAST_1)
                    .forcePathStyle(true)
                    .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(credentials.accessKey, credentials.secretKey)),
                    ).httpClient(UrlConnectionHttpClient.create())
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .build()
            admin.createBucket { it.bucket(bucket) }

            // 台帳: 取引先の ID ごとに、送り先(Toxiproxy の宛先)・ホスト鍵・鍵の名前を持つ
            val proxyPort = toxiproxy.getMappedPort(PROXY_PORT)
            val registry =
                PartnerRegistry
                    .parse(
                        TestLedgers.json(
                            TestLedgers.Partner(id = PARTNER.value, host = toxiproxy.host, port = proxyPort, outbound = OUTBOX),
                        ),
                    ).ok()
            val secrets =
                EnvSecretProvider(
                    mapOf(
                        "P01_KNOWN_HOSTS" to "[${toxiproxy.host}]:$proxyPort ${sftp.knownHostsLine().substringAfter(' ')}",
                        "P01_SSH_KEY" to client.privateKey,
                        "P01_PGP_PUBLIC_KEY" to partnerPgp.publicKey,
                        "OWN_PGP_SECRET_KEY" to ours.secretKey,
                        "OWN_PGP_PASSPHRASE" to ours.passphrase,
                    ),
                )
            val outbox =
                SftpPartnerOutbox(
                    registry,
                    exchange,
                    prefixes.archive,
                    OwnPgpKey(SecretName("OWN_PGP_SECRET_KEY"), SecretName("OWN_PGP_PASSPHRASE")),
                    secrets,
                    mapOf(PARTNER to resilience),
                )
            deliver = DeliverFileService(files, registry, deliveries, outbox)
        }

        afterSpec {
            exchange.close()
            admin.close()
            toxiproxy.stop()
            sftp.stop()
            seaweed.stop()
            postgres.stop()
            network.close()
            workDir.toFile().deleteRecursively()
        }

        test("合格のファイルを、取引先の公開鍵で暗号化・自社の鍵で署名して送る。取引先は署名を確かめて復号でき、中身は archive と同じ") {
            val name = received()
            deliver(name).ok().delivered shouldBe listOf(PartnerDelivery(PARTNER, DeliveryOutcome.SENT))

            deliveredIntact(name) shouldBe true
            sftp.list(OUTBOX).filter { it.startsWith(name) } shouldContainExactly listOf("$name.parquet.pgp")
            row(name) shouldBe ("SENT" to false)

            // やり直しても送り直さない(送信済み)
            deliver(name).ok().delivered shouldBe listOf(PartnerDelivery(PARTNER, DeliveryOutcome.ALREADY_SENT))
        }

        test("正式名に変えた後、送信済みを記録する前に落ちた: やり直しでは相手の側の正式名を見て、送らずに送信済みにする") {
            val name = received()
            val remote = Delivery.remoteName("$name.parquet")
            // 前の実行: 送信中を記録し、正式名まで送り終えたところで落ちた(相手の側のファイルは、前の実行の暗号文)
            deliveries.begin(name, PARTNER, remote).ok().state shouldBe DeliveryState.SENDING
            val previous = "previous-run-ciphertext".toByteArray()
            sftp.writeFile("$OUTBOX/$remote", previous)

            deliver(name).ok().delivered shouldBe listOf(PartnerDelivery(PARTNER, DeliveryOutcome.ALREADY_PRESENT))

            // 送り直していない(相手の側のファイルは前の実行のまま)
            sftp.readFile("$OUTBOX/$remote")!!.contentEquals(previous) shouldBe true
            row(name) shouldBe ("SENT" to true)
        }

        test("一時名(.part)まで書いて落ちた: 正式名はないので、送り直して正式名にする") {
            val name = received()
            val remote = Delivery.remoteName("$name.parquet")
            deliveries.begin(name, PARTNER, remote).ok()
            sftp.writeFile("$OUTBOX/$remote.part", "partial".toByteArray())

            deliver(name).ok().delivered shouldBe listOf(PartnerDelivery(PARTNER, DeliveryOutcome.SENT))

            deliveredIntact(name) shouldBe true
            sftp.list(OUTBOX).filter { it.startsWith(name) } shouldContainExactly listOf(remote)
            row(name) shouldBe ("SENT" to false)
        }

        test("取引先に接続できなければ、リトライして Circuit Breaker が開く。回復後に Half-Open を経て閉じ、送り終える(メトリクスに出る)") {
            val name = received()
            val retriesBefore = counter("eia.resilience.retries") { it["eia.dependency.name"] == "sftp-$PARTNER" }
            val closedToOpen = transitions("closed", "open")
            val openToHalfOpen = transitions("open", "half_open")
            val halfOpenToClosed = transitions("half_open", "closed")
            breaker.state shouldBe CircuitState.CLOSED

            // 切断(Toxiproxy が接続を受け付けない)。送れなかった配送は送信中のまま残る
            proxy.disable()
            var attempts = 0
            while (breaker.state != CircuitState.OPEN && attempts < 3) {
                val report = deliver(name).ok()
                report.delivered.shouldBeEmpty()
                report.failures
                    .single()
                    .error
                    .shouldBeInstanceOf<RemoteUnavailable>()
                attempts++
            }
            breaker.state shouldBe CircuitState.OPEN
            row(name) shouldBe ("SENDING" to false)

            // Open の間は取引先に接続せず、すぐに circuit_open を返す
            deliver(name)
                .ok()
                .failures
                .single()
                .error
                .shouldBeInstanceOf<CircuitOpen>()

            // 回復: 接続を戻し、Open の期間の後の最初の呼び出しで Half-Open になり、成功して Closed に戻る
            proxy.enable()
            delay(CIRCUIT_BREAKER.openDuration)
            deliver(name).ok().delivered shouldBe listOf(PartnerDelivery(PARTNER, DeliveryOutcome.SENT))
            breaker.state shouldBe CircuitState.CLOSED
            deliveredIntact(name) shouldBe true
            row(name) shouldBe ("SENT" to false)

            // メトリクス: リトライと、Closed → Open → Half-Open → Closed の遷移(依存先の名前 sftp-partner01)。
            // リトライの回数は、窓に残っている前のテストの成功の数で変わる(Open になった後のリトライは見送る)ので、1 回以上とだけ確かめる
            counter("eia.resilience.retries") { it["eia.dependency.name"] == "sftp-$PARTNER" } - retriesBefore shouldBeGreaterThanOrEqual 1L
            transitions("closed", "open") - closedToOpen shouldBe 1L
            transitions("open", "half_open") - openToHalfOpen shouldBe 1L
            transitions("half_open", "closed") - halfOpenToClosed shouldBe 1L
        }
    })
