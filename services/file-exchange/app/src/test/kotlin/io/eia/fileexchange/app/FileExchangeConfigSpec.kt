package io.eia.fileexchange.app

import io.eia.fileexchange.adapters.file.InboxPrefixes
import io.eia.fileexchange.application.port.inbound.DeliverFileUseCase
import io.eia.fileexchange.application.port.inbound.DeliveryReport
import io.eia.fileexchange.application.port.inbound.PartnerDelivery
import io.eia.fileexchange.application.port.inbound.PartnerFailure
import io.eia.fileexchange.application.port.inbound.ReceiveFileUseCase
import io.eia.fileexchange.application.port.inbound.ReceiveOutcome
import io.eia.fileexchange.application.port.inbound.SweepReport
import io.eia.fileexchange.domain.DeliveryOutcome
import io.eia.fileexchange.domain.ExtraArrival
import io.eia.fileexchange.domain.ExtraArrivalReason
import io.eia.fileexchange.domain.PartnerId
import io.eia.fileexchange.domain.ReceiptDestination
import io.eia.fileexchange.domain.ReceiptState
import io.eia.fileexchange.domain.ReceivedFile
import io.eia.platform.batch.StepName
import io.eia.platform.batch.Trigger
import io.eia.platform.filetransfer.store.ObjectReader
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.UnavailableError
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun <T, E> Result<T, E>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun <T, E> Result<T, E>.failure(): E =
    when (this) {
        is Result.Ok -> fail("Err を期待しましたが Ok でした: $value")
        is Result.Err -> error
    }

class FileExchangeConfigSpec :
    FunSpec({
        val minimal = mapOf(FileExchangeConfig.DB_URL to "jdbc:postgresql://postgres:5432/file_exchange")

        test("既定: 受け口・archive・quarantine は sales-daily の下。30 秒ごとに到着を、5 分ごとに見回り") {
            val config = FileExchangeConfig.fromEnvironment(minimal).ok()
            config.prefixes shouldBe InboxPrefixes("inbox/sales-daily/", "archive/sales-daily/", "quarantine/sales-daily/")
            config.bucket shouldBe "eiaf-file-exchange"
            config.pollInterval shouldBe 30.seconds
            config.sweepInterval shouldBe 5.minutes
            config.healthPort shouldBe 8081
            config.appUser shouldBe "file_exchange_app"
            config.sftpOpenDuration shouldBe 30.seconds
            config.deliverRetryDelay shouldBe 5.seconds
            config
                .forRun()
                .failure()
                .violations
                .single()
                .field shouldBe FileExchangeConfig.S3_ENDPOINT
        }

        test("設定を変えられる。誤りは項目ごとにまとめて知らせる") {
            val config =
                FileExchangeConfig
                    .fromEnvironment(
                        minimal +
                            mapOf(
                                FileExchangeConfig.S3_ENDPOINT to "http://seaweedfs:8333",
                                FileExchangeConfig.INBOX_PREFIX to "inbox/a/",
                                FileExchangeConfig.SWEEP_INTERVAL to "1m",
                                FileExchangeConfig.SFTP_OPEN_DURATION to "5s",
                                FileExchangeConfig.DELIVER_RETRY_DELAY to "1s",
                            ),
                    ).ok()
            config.sftpOpenDuration shouldBe 5.seconds
            config.deliverRetryDelay shouldBe 1.seconds
            config.forRun().ok().toString() shouldBe "http://seaweedfs:8333"
            config.prefixes.inbox shouldBe "inbox/a/"
            config.sweepInterval shouldBe 1.minutes

            FileExchangeConfig
                .fromEnvironment(
                    mapOf(
                        FileExchangeConfig.S3_ENDPOINT to "seaweedfs:8333",
                        FileExchangeConfig.ARCHIVE_PREFIX to "/archive",
                        FileExchangeConfig.HEALTH_PORT to "99999",
                        FileExchangeConfig.POLL_INTERVAL to "-1s",
                        FileExchangeConfig.SFTP_OPEN_DURATION to "soon",
                    ),
                ).failure()
                .violations
                .map { it.field } shouldContainExactlyInAnyOrder
                listOf(
                    FileExchangeConfig.DB_URL,
                    FileExchangeConfig.S3_ENDPOINT,
                    FileExchangeConfig.ARCHIVE_PREFIX,
                    FileExchangeConfig.HEALTH_PORT,
                    FileExchangeConfig.POLL_INTERVAL,
                    FileExchangeConfig.SFTP_OPEN_DURATION,
                )
            FileExchangeConfig
                .fromEnvironment(minimal + (FileExchangeConfig.QUARANTINE_PREFIX to "inbox/sales-daily/"))
                .failure()
                .violations
                .single()
                .field shouldBe FileExchangeConfig.INBOX_PREFIX
        }

        test("受け取りの DAG: 受け口のマニフェストの到着で起動し、データファイルがなくても起動する(欠損は検査で不合格)") {
            val reader = InMemoryMetricReader.create()
            val metrics =
                FileExchangeMetrics(
                    SdkMeterProvider
                        .builder()
                        .registerMetricReader(reader)
                        .build()
                        .get("test"),
                )
            val receive =
                object : ReceiveFileUseCase {
                    override suspend fun invoke(name: String) =
                        ok(
                            ReceiveOutcome(
                                ReceivedFile(name, null, ReceiptDestination.QUARANTINE, null, "data_missing", "なし", ReceiptState.STORED),
                                resumed = false,
                                alreadyStored = false,
                            ),
                        )
                }
            val deliver =
                object : DeliverFileUseCase {
                    override suspend fun invoke(name: String) = ok(DeliveryReport(name, true, emptyList(), emptyList()))
                }
            val dag = ReceiveJob.dag(receive, deliver, metrics)
            dag.id shouldBe ReceiveJob.DAG_ID
            dag.integrationId shouldBe "INT-SALES-004"
            dag.steps.map { it.name } shouldBe listOf(StepName("receive"), StepName("deliver"))
            dag.step(StepName("deliver")).dependsOn shouldBe listOf(StepName("receive"))
            dag.step(StepName("deliver")).retry.initialDelay shouldBe 5.seconds
            ReceiveJob
                .dag(receive, deliver, metrics, deliverRetryDelay = 1.seconds)
                .step(StepName("deliver"))
                .retry.initialDelay shouldBe 1.seconds
            val source = object : ObjectReader by FakeReader {}
            val trigger = ReceiveJob.job(dag, source, "inbox/sales-daily/").trigger.shouldBeInstanceOf<Trigger.FileArrival>()
            trigger.prefix shouldBe "inbox/sales-daily/"
            trigger.requireDataFile shouldBe false
        }

        test("取引先の台帳: 設定がなければ取引先なし(配送しない)。ファイルを読めない・形が誤っていれば設定の誤り") {
            FileExchangeConfig
                .fromEnvironment(minimal)
                .ok()
                .partners()
                .ok()
                .partners shouldBe emptyList()

            val dir = Files.createTempDirectory("partners")
            try {
                val file = dir.resolve("partners.json")
                Files.writeString(
                    file,
                    """
                    {"partners": [{"id": "partner01", "datasets": ["sales_daily"],
                      "sftp": {"host": "sftp-b2b", "port": 2222, "username": "partner01", "directory": "/config/outbox",
                               "knownHostsSecret": "P_KNOWN_HOSTS", "privateKeySecret": "P_SSH_KEY"},
                      "pgp": {"publicKeySecret": "P_PGP_PUBLIC_KEY"}}]}
                    """.trimIndent(),
                )
                val config = FileExchangeConfig.fromEnvironment(minimal + (FileExchangeConfig.PARTNERS_FILE to file.toString())).ok()
                config.partners().ok().recipients("sales_daily") shouldBe listOf(PartnerId("partner01"))

                Files.writeString(file, "{}")
                config
                    .partners()
                    .failure()
                    .violations
                    .single()
                    .field shouldBe "partners"
                FileExchangeConfig
                    .fromEnvironment(minimal + (FileExchangeConfig.PARTNERS_FILE to dir.resolve("none.json").toString()))
                    .ok()
                    .partners()
                    .failure()
                    .violations
                    .single()
                    .field shouldBe FileExchangeConfig.PARTNERS_FILE
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("メトリクス: 取引先への配送の数(取引先と結果ごと。送れなかったものは failed)") {
            val reader = InMemoryMetricReader.create()
            val metrics =
                FileExchangeMetrics(
                    SdkMeterProvider
                        .builder()
                        .registerMetricReader(reader)
                        .build()
                        .get("test"),
                )
            val a = PartnerId("partner-a")
            val b = PartnerId("partner-b")
            metrics.delivered(
                DeliveryReport(
                    "a_b_20261014150000_001",
                    false,
                    listOf(PartnerDelivery(a, DeliveryOutcome.SENT), PartnerDelivery(b, DeliveryOutcome.ALREADY_PRESENT)),
                    listOf(PartnerFailure(b, UnavailableError("x"))),
                ),
            )
            reader
                .collectAllMetrics()
                .single { it.name == "eia.file_exchange.deliveries" }
                .longSumData.points
                .map { it.attributes.get(PARTNER)!! to it.attributes.get(OUTCOME)!! } shouldContainExactlyInAnyOrder
                listOf("partner-a" to "sent", "partner-b" to "already_present", "partner-b" to "failed")
        }

        test("メトリクス: 受け取った数(移動先と理由)・受け取り済みの名前で届いた数・受け口の滞留") {
            val reader = InMemoryMetricReader.create()
            val now = Instant.parse("2026-10-16T00:10:00Z")
            val clock =
                object : Clock {
                    override fun now() = now
                }
            val metrics =
                FileExchangeMetrics(
                    SdkMeterProvider
                        .builder()
                        .registerMetricReader(reader)
                        .build()
                        .get("test"),
                    clock,
                )
            val archived =
                ReceivedFile(
                    "a_b_20261014150000_001",
                    "a".repeat(64),
                    ReceiptDestination.ARCHIVE,
                    "x.parquet",
                    null,
                    null,
                    ReceiptState.STORED,
                )
            metrics.received(ReceiveOutcome(archived, resumed = false, alreadyStored = false))
            metrics.received(ReceiveOutcome(archived, resumed = false, alreadyStored = true))
            metrics.swept(
                SweepReport(
                    listOf(ExtraArrival(1, "a_b_20261014150000_001", null, ExtraArrivalReason.NAME_REUSED)),
                    waiting = 2,
                    oldestCreatedAt = Instant.parse("2026-10-16T00:00:00Z"),
                ),
            )
            val collected = reader.collectAllMetrics().associateBy { it.name }
            collected
                .getValue("eia.file_exchange.files.received")
                .longSumData.points
                .single()
                .value shouldBe 1
            collected
                .getValue("eia.file_exchange.files.extra")
                .longSumData.points
                .single()
                .value shouldBe 1
            collected
                .getValue("eia.file_exchange.inbox.waiting")
                .longGaugeData.points
                .single()
                .value shouldBe 2
            collected
                .getValue("eia.file_exchange.inbox.oldest_age")
                .doubleGaugeData.points
                .single()
                .value shouldBe 600.0
        }
    })

private val PARTNER: AttributeKey<String> = AttributeKey.stringKey("file_exchange.partner")
private val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("file_exchange.outcome")

private object FakeReader : ObjectReader {
    override fun list(prefix: String) = ok(emptyList<io.eia.platform.filetransfer.store.StoredObject>())

    override fun read(
        key: String,
        maxBytes: Int,
    ) = ok(null)
}
