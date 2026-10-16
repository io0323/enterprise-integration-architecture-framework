package io.eia.platform.batch

import io.eia.platform.batch.sla.SlaMonitor
import io.eia.platform.filetransfer.FileTransferError
import io.eia.platform.filetransfer.StoreUnavailable
import io.eia.platform.filetransfer.store.ObjectReader
import io.eia.platform.filetransfer.store.StoredObject
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** テスト用の置き場。 */
private class FakeObjectSource : ObjectReader {
    val objects = sortedMapOf<String, ByteArray>()
    val reads = mutableListOf<String>()
    var failing: FileTransferError? = null

    override fun list(prefix: String): Result<List<StoredObject>, FileTransferError> =
        failing?.let { err(it) }
            ?: ok(objects.filterKeys { it.startsWith(prefix) }.map { (key, body) -> StoredObject(key, body.size.toLong()) })

    override fun read(
        key: String,
        maxBytes: Int,
    ): Result<ByteArray?, FileTransferError> {
        reads += key
        return ok(objects[key]?.let { it.copyOf(minOf(it.size, maxBytes + 1)) })
    }
}

class BatchSchedulerSpec :
    FunSpec({
        val telemetry = Telemetry()
        val tokyo = TimeZone.of("Asia/Tokyo")
        // 2026-10-14 01:30 JST
        val now = Instant.parse("2026-10-13T16:30:00Z")
        val day = PartitionKey("2026-10-13")
        val noop = StepAction { ok(Unit) }
        val ingest = dag("test.ingest") { step("load") { action(noop) } }
        val transform = dag("test.transform") { step("build") { action(noop) } }
        val files = dag("test.files") { step("read") { action(noop) } }
        val traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"

        fun manifest(
            file: String,
            sizeBytes: Long? = null,
        ): ByteArray =
            """
            {"file":"$file","recordCount":3,"sha256":"${"a".repeat(64)}","schemaVersion":"sales_daily.v1",
             "createdAt":"2026-10-13T16:00:00Z","traceparent":"$traceparent","correlationId":"corr-file"
             ${sizeBytes?.let { ""","sizeBytes":$it""" } ?: ""}, "extra":"ignored"}
            """.trimIndent().toByteArray()

        fun scheduler(
            store: InMemoryBatchStore,
            jobs: List<BatchJob>,
            clock: TestClock = TestClock(now),
        ) = BatchScheduler(
            BatchRunner(store, telemetry.runtime, clock),
            store,
            jobs,
            clock,
            settings = BatchScheduler.Settings(pollInterval = 10.milliseconds),
        )

        fun record(
            dag: DagDefinition,
            status: RunStatus,
            generation: Int = 1,
            trigger: RunTrigger = RunTrigger.MANUAL,
        ) = RunRecord(RunKey(dag.id, day), status, generation, trigger, "00-${"1".repeat(32)}-${"2".repeat(16)}-01", now, now)

        test("時刻: 直近の起動のパーティションの記録がないか RUNNING のまま残っていれば起動し、成功・失敗していれば起動しない") {
            val store = InMemoryBatchStore()
            val job = BatchJob(ingest, Trigger.Scheduled(Schedule.daily(LocalTime(1, 0), tokyo)))
            val scheduler = scheduler(store, listOf(job))

            val due = scheduler.due().single()
            due.partition shouldBe day
            due.trigger.kind shouldBe TriggerKind.SCHEDULE
            due.trigger.attributes shouldBe mapOf(BatchScheduler.SCHEDULED_AT to "2026-10-13T16:00:00Z")

            store.runs[RunKey(ingest.id, day)] = record(ingest, RunStatus.RUNNING)
            scheduler.due().map { it.partition } shouldContainExactly listOf(day)
            store.runs[RunKey(ingest.id, day)] = record(ingest, RunStatus.FAILED)
            scheduler.due().shouldBeEmpty()
            store.runs[RunKey(ingest.id, day)] = record(ingest, RunStatus.SUCCEEDED)
            scheduler.due().shouldBeEmpty()
        }

        test("前段の完了: 前段が成功したパーティションで、前段の span を親にして起動する。前段が作り直したら後段も作り直す") {
            val store = InMemoryBatchStore()
            val scheduler = scheduler(store, listOf(BatchJob(transform, Trigger.AfterDag(ingest.id))))
            scheduler.due().shouldBeEmpty()
            store.runs[RunKey(ingest.id, day)] =
                record(ingest, RunStatus.SUCCEEDED, trigger = RunTrigger(TriggerKind.SCHEDULE, correlationId = "corr-1"))

            val due = scheduler.due().single()
            due.mode shouldBe RunMode.RESUME
            due.trigger.kind shouldBe TriggerKind.UPSTREAM
            due.trigger.traceparent shouldBe "00-${"1".repeat(32)}-${"2".repeat(16)}-01"
            due.trigger.correlationId shouldBe "corr-1"
            due.trigger.attributes shouldBe mapOf(BatchScheduler.UPSTREAM_DAG to "test.ingest", BatchScheduler.UPSTREAM_GENERATION to "1")

            store.runs[RunKey(transform.id, day)] = record(transform, RunStatus.SUCCEEDED, trigger = due.trigger)
            scheduler.due().shouldBeEmpty()

            store.runs[RunKey(ingest.id, day)] = record(ingest, RunStatus.SUCCEEDED, generation = 2)
            val rebuild = scheduler.due().single()
            rebuild.mode shouldBe RunMode.RESTART
            rebuild.trigger.attributes[BatchScheduler.UPSTREAM_GENERATION] shouldBe "2"
        }

        test("ファイルの到着: 正しいマニフェストとデータファイルが揃えば、マニフェストの traceparent と属性で起動する") {
            val store = InMemoryBatchStore()
            val source = FakeObjectSource()
            val scheduler = scheduler(store, listOf(BatchJob(files, Trigger.FileArrival(source, "inbound/"))))
            val name = "sales_daily_20261014010000_001"
            source.objects["inbound/$name.csv"] = ByteArray(10)
            source.objects["inbound/$name.manifest.json"] = manifest("$name.csv", sizeBytes = 10)

            val due = scheduler.due().single()

            due.partition shouldBe PartitionKey(name)
            due.trigger.kind shouldBe TriggerKind.FILE
            due.trigger.traceparent shouldBe traceparent
            due.trigger.correlationId shouldBe "corr-file"
            due.trigger.attributes shouldBe
                mapOf(
                    BatchScheduler.FILE_KEY to "inbound/$name.csv",
                    BatchScheduler.FILE_MANIFEST to "inbound/$name.manifest.json",
                    BatchScheduler.FILE_SHA256 to "a".repeat(64),
                    BatchScheduler.FILE_RECORD_COUNT to "3",
                    BatchScheduler.FILE_SCHEMA_VERSION to "sales_daily.v1",
                )

            // 実行があれば、マニフェストを読み直さない
            store.runs[RunKey(files.id, PartitionKey(name))] = record(files, RunStatus.SUCCEEDED)
            source.reads.clear()
            scheduler.due().shouldBeEmpty()
            source.reads.shouldBeEmpty()
        }

        test("ファイルの到着: マニフェストの誤り・データファイルがない・大きさが違う・名前が違うときは起動しない") {
            val store = InMemoryBatchStore()
            val source = FakeObjectSource()
            val scheduler = scheduler(store, listOf(BatchJob(files, Trigger.FileArrival(source, ""))))
            source.objects["sales_daily_20261014010000_001.manifest.json"] = "{\"file\":1}".toByteArray()
            source.objects["sales_daily_20261014010000_002.manifest.json"] = manifest("sales_daily_20261014010000_002.csv")
            source.objects["sales_daily_20261014010000_003.csv"] = ByteArray(5)
            source.objects["sales_daily_20261014010000_003.manifest.json"] = manifest("sales_daily_20261014010000_003.csv", 6)
            source.objects["sales_daily_20261014010000_004.manifest.json"] = manifest("sales_daily_20261014010000_003.csv")
            source.objects["bad name!.manifest.json"] = manifest("sales_daily_20261014010000_003.csv")

            scheduler.due().shouldBeEmpty()
            // 2 回目もログを繰り返さずに同じ判定
            scheduler.due().shouldBeEmpty()

            source.failing = StoreUnavailable("SlowDown")
            scheduler.due().shouldBeEmpty()
        }

        test("ファイルの到着(requireDataFile = false): データファイルがない・大きさが違っても起動し、判定を DAG に任せる。名前の誤りは起動しない") {
            val store = InMemoryBatchStore()
            val source = FakeObjectSource()
            val scheduler = scheduler(store, listOf(BatchJob(files, Trigger.FileArrival(source, "", requireDataFile = false))))
            source.objects["sales_daily_20261014010000_001.manifest.json"] = manifest("sales_daily_20261014010000_001.csv")
            source.objects["sales_daily_20261014010000_002.csv"] = ByteArray(5)
            source.objects["sales_daily_20261014010000_002.manifest.json"] = manifest("sales_daily_20261014010000_002.csv", 6)
            source.objects["sales_daily_20261014010000_003.manifest.json"] = manifest("sales_daily_20261014010000_004.csv")

            scheduler.due().map { it.partition.value } shouldContainExactlyInAnyOrder
                listOf("sales_daily_20261014010000_001", "sales_daily_20261014010000_002")
        }

        test("run は起動の条件を繰り返し確かめて実行し、SLA の予測も更新する。前段の完了で後段が続けて動く") {
            val store = InMemoryBatchStore()
            val ran = AtomicInteger()
            val counted = dag("test.counted") { step("count") { action { ran.incrementAndGet().let { ok(Unit) } } } }
            val jobs =
                listOf(
                    BatchJob(ingest, Trigger.Scheduled(Schedule.daily(LocalTime(1, 0), tokyo))),
                    BatchJob(counted, Trigger.AfterDag(ingest.id)),
                )
            val clock = TestClock(now)
            val scheduler =
                BatchScheduler(
                    BatchRunner(store, telemetry.runtime, clock),
                    store,
                    jobs,
                    clock,
                    SlaMonitor(jobs, store, clock),
                    BatchScheduler.Settings(pollInterval = 10.milliseconds, lookback = 1.days),
                )

            coroutineScope {
                val loop = launch(Dispatchers.Default) { scheduler.run() }
                withTimeout(5.seconds) {
                    while (store.runs[RunKey(counted.id, day)]?.status != RunStatus.SUCCEEDED) delay(10.milliseconds)
                }
                // 何回まわっても同じパーティションは 1 回だけ
                delay(50.milliseconds)
                loop.cancelAndJoin()
            }
            ran.get() shouldBe 1
            store.runs.getValue(RunKey(ingest.id, day)).status shouldBe RunStatus.SUCCEEDED
        }
    })
