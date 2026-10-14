@file:Suppress("MagicNumber") // テストデータの大きさ

package io.eia.platform.batch

import io.eia.platform.batch.file.FileManifest
import io.eia.platform.batch.file.S3ObjectSource
import io.eia.platform.batch.file.S3ObjectSourceConfig
import io.eia.platform.batch.file.StoredObject
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.TelemetrySinks
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.SecretName
import io.eia.platform.testsupport.SeaweedFsContainer
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.getOrNull
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * ファイルの到着での起動(ADR-0030 §4)を、実際の S3 互換ストレージ(SeaweedFS)で確かめる。
 * 監視は読み取り専用の identity で行い、データファイル・マニフェストは書込み用の identity で置く(データファイルを先、マニフェストを最後)。
 */
class FileArrivalIT :
    FunSpec({
        val bucket = "eiaf-inbound"
        val seaweed =
            SeaweedFsContainer(
                mapOf(
                    "eiaf" to listOf("Admin", "Read", "Write", "List"),
                    "eiaf-batch-reader" to listOf("Read:$bucket", "List:$bucket"),
                    "eiaf-other" to listOf("Read:other", "List:other"),
                ),
            )
        lateinit var admin: S3Client
        val runtime = Observability.init(ObservabilityConfig.of("it-batch").getOrNull()!!, TelemetrySinks(), installLogAppender = false)
        val accessKey = SecretName("IT_BATCH_S3_ACCESS_KEY")
        val secretKey = SecretName("IT_BATCH_S3_SECRET_KEY")
        val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

        fun source(identity: String): S3ObjectSource {
            val credentials = seaweed.credentials.getValue(identity)
            return S3ObjectSource(
                S3ObjectSourceConfig(URI(seaweed.endpoint), bucket, accessKey, secretKey),
                EnvSecretProvider(mapOf(accessKey.value to credentials.accessKey, secretKey.value to credentials.secretKey)),
            )
        }

        fun put(
            key: String,
            body: ByteArray,
        ) {
            admin.putObject({ it.bucket(bucket).key(key) }, RequestBody.fromBytes(body))
        }

        beforeSpec {
            seaweed.start()
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
        }

        afterSpec {
            admin.close()
            seaweed.stop()
        }

        test("マニフェストが届いたら、マニフェストの traceparent・データファイルのキーで起動する。実行があれば起動しない") {
            val name = "sales_daily_20261014010000_001"
            val data = "id,amount\n1,100\n2,200\n".toByteArray()
            put("daily/$name.csv", data)
            val store = RunsOnlyStore()
            val files = dag("it.files") { step("read") { action { ok(Unit) } } }
            val scheduler =
                BatchScheduler(
                    BatchRunner(store, runtime),
                    store,
                    listOf(BatchJob(files, Trigger.FileArrival(source("eiaf-batch-reader"), "daily/"))),
                )

            // データファイルだけでは起動しない(マニフェストの到着が完了の合図)
            scheduler.due().shouldBeEmpty()
            put(
                "daily/$name.manifest.json",
                """
                {"file":"$name.csv","recordCount":2,"sha256":"${"0".repeat(64)}","schemaVersion":"sales_daily.v1",
                 "createdAt":"2026-10-13T16:00:00Z","traceparent":"$traceparent","correlationId":"corr-it","sizeBytes":${data.size}}
                """.trimIndent().toByteArray(),
            )
            val due = scheduler.due().single()

            due.partition shouldBe PartitionKey(name)
            due.trigger.traceparent shouldBe traceparent
            due.trigger.correlationId shouldBe "corr-it"
            due.trigger.attributes[BatchScheduler.FILE_KEY] shouldBe "daily/$name.csv"
            due.trigger.attributes[BatchScheduler.FILE_RECORD_COUNT] shouldBe "2"

            store.runs[due.key] = RunRecord(due.key, RunStatus.SUCCEEDED, 1, due.trigger, null, Instant.DISTANT_PAST, Instant.DISTANT_PAST)
            scheduler.due().shouldBeEmpty()
        }

        test("置き場: 一覧はキーの順。ないキーは null。大きすぎるオブジェクトは上限 + 1 バイトまで読む") {
            val reader = source("eiaf-batch-reader")
            put("list/b.txt", "b".toByteArray())
            put("list/a.txt", ByteArray(FileManifest.MAX_BYTES + 10))

            reader.list("list/").getOrNull() shouldBe
                listOf(StoredObject("list/a.txt", FileManifest.MAX_BYTES + 10L), StoredObject("list/b.txt", 1))
            reader.read("list/none.txt", 10).getOrNull().shouldBeNull()
            reader.read("list/a.txt", FileManifest.MAX_BYTES).getOrNull()!!.size shouldBe FileManifest.MAX_BYTES + 1
        }

        test("権限のない identity は拒否(リトライしない)、資格情報がなければ一時的な失敗") {
            source("eiaf-other").list("").shouldBeInstanceOf<Result.Err<BatchError>>().error shouldBe BatchSourceRejected("AccessDenied")
            val missing =
                S3ObjectSource(S3ObjectSourceConfig(URI(seaweed.endpoint), bucket, accessKey, secretKey), EnvSecretProvider(emptyMap()))
            missing
                .list("")
                .shouldBeInstanceOf<Result.Err<BatchError>>()
                .error
                .shouldBeInstanceOf<BatchSourceUnavailable>()
            missing.close()
        }
    })

/** 実行の記録を探すだけの [BatchStore](起動の判定の確認用。ほかの操作は使わない)。 */
@Suppress("TooManyFunctions") // BatchStore の実装(判定に使わない操作は unused)
private class RunsOnlyStore : BatchStore {
    val runs = mutableMapOf<RunKey, RunRecord>()

    override fun find(run: RunKey): Result<RunRecord?, BatchError> = ok(runs[run])

    override fun tryLock(run: RunKey): Result<RunLease?, BatchError> = unused()

    override fun begin(
        run: RunKey,
        trigger: RunTrigger,
        runTraceparent: String?,
        restart: Boolean,
        now: Instant,
    ): Result<RunRecord, BatchError> = unused()

    override fun steps(run: RunKey): Result<Map<StepName, StepRecord>, BatchError> = unused()

    override fun stepStarted(
        run: RunKey,
        step: StepName,
        attempt: Int,
        now: Instant,
    ): Result<Unit, BatchError> = unused()

    override fun stepFinished(
        run: RunKey,
        step: StepName,
        status: StepStatus,
        now: Instant,
    ): Result<Unit, BatchError> = unused()

    override fun finish(
        run: RunKey,
        status: RunStatus,
        now: Instant,
    ): Result<Unit, BatchError> = unused()

    override fun readCheckpoint(key: CheckpointKey): Result<String?, BatchError> = unused()

    override fun writeCheckpoint(
        key: CheckpointKey,
        value: String,
        now: Instant,
    ): Result<Unit, BatchError> = unused()

    override fun recentDurations(
        dag: DagId,
        step: StepName,
        limit: Int,
    ): Result<List<Duration>, BatchError> = unused()

    override fun runs(
        dag: DagId,
        status: RunStatus,
        since: Instant,
    ): Result<List<RunRecord>, BatchError> = unused()

    private fun unused(): Nothing = error("起動の判定では使いません")
}
