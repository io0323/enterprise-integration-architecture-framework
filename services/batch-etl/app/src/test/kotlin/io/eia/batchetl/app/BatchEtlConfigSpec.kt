package io.eia.batchetl.app

import io.eia.batchetl.application.port.inbound.BuildDailyOrderMartUseCase
import io.eia.batchetl.application.port.inbound.IngestOrderEventsUseCase
import io.eia.batchetl.application.port.inbound.IngestReport
import io.eia.batchetl.application.port.inbound.MartBuildReport
import io.eia.batchetl.domain.BusinessDay
import io.eia.platform.batch.StepName
import io.eia.platform.batch.Trigger
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ok
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlinx.datetime.LocalTime
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

class BatchEtlConfigSpec :
    FunSpec({
        val minimal = mapOf(BatchEtlConfig.DB_URL to "jdbc:postgresql://postgres:5432/batch_etl")

        test("既定: 01:00 JST に起動し、06:00 JST までに終える。30 秒ごとに確かめる") {
            val config = BatchEtlConfig.fromEnvironment(minimal).ok()

            config.ownerUser shouldBe "batch_etl"
            config.appUser shouldBe "batch_etl_app"
            config.healthPort shouldBe 8081
            config.dailyAt shouldBe LocalTime(1, 0)
            config.slaAt shouldBe LocalTime(6, 0)
            config.pollInterval shouldBe 30.seconds
            config
                .forRun()
                .failure()
                .violations
                .map { it.field } shouldBe
                listOf(BatchEtlConfig.KAFKA_BOOTSTRAP, BatchEtlConfig.SCHEMA_REGISTRY_URL)
        }

        test("設定の誤りは、項目ごとにまとめて知らせる") {
            val error =
                BatchEtlConfig
                    .fromEnvironment(
                        mapOf(
                            BatchEtlConfig.HEALTH_PORT to "70000",
                            BatchEtlConfig.DAILY_AT to "25:00",
                            BatchEtlConfig.SLA_AT to "6:00",
                            BatchEtlConfig.POLL_INTERVAL to "-1s",
                        ),
                    ).failure()

            error.violations.map { it.field } shouldContainExactlyInAnyOrder
                listOf(
                    BatchEtlConfig.DB_URL,
                    BatchEtlConfig.HEALTH_PORT,
                    BatchEtlConfig.DAILY_AT,
                    BatchEtlConfig.SLA_AT,
                    BatchEtlConfig.POLL_INTERVAL,
                )
        }

        test("設定を変えられる。serve・run の設定がそろう") {
            val config =
                BatchEtlConfig
                    .fromEnvironment(
                        minimal +
                            mapOf(
                                BatchEtlConfig.KAFKA_BOOTSTRAP to "kafka:9092",
                                BatchEtlConfig.SCHEMA_REGISTRY_URL to "http://apicurio:8080/apis/registry/v3",
                                BatchEtlConfig.DAILY_AT to "02:30",
                                BatchEtlConfig.SLA_AT to "07:15",
                                BatchEtlConfig.POLL_INTERVAL to "10s",
                            ),
                    ).ok()

            config.dailyAt shouldBe LocalTime(2, 30)
            config.slaAt shouldBe LocalTime(7, 15)
            config.forRun().ok() shouldBe RunSettings("kafka:9092", "http://apicurio:8080/apis/registry/v3")
        }

        test("日次の DAG: 取り込み → 変換。SLA は翌日の期限。毎日の時刻で前日の分を起動する") {
            val reader = InMemoryMetricReader.create()
            val metrics =
                BatchEtlMetrics(
                    SdkMeterProvider
                        .builder()
                        .registerMetricReader(reader)
                        .build()
                        .get("test"),
                )
            val ingest =
                object : IngestOrderEventsUseCase {
                    override suspend fun invoke() = ok(IngestReport(3, 1))
                }
            val day = BusinessDay.parse("2026-10-13").ok()
            val build =
                object : BuildDailyOrderMartUseCase {
                    override suspend fun invoke(day: BusinessDay) =
                        ok(MartBuildReport(listOf(day.minusDays(2) to 1, day to 2), listOf(day.minusDays(40))))
                }
            val dag = OrderDailyJob.dag(ingest, build, metrics, LocalTime(6, 0))

            dag.steps.map { it.name } shouldBe listOf(StepName("ingest"), StepName("transform"))
            dag.step(StepName("transform")).dependsOn shouldBe listOf(StepName("ingest"))
            dag.step(StepName("ingest")).expected shouldBe 5.minutes
            dag.sla!!.deadline.of(
                io.eia.platform.batch
                    .PartitionKey("2026-10-13"),
            ) shouldBe Instant.parse("2026-10-13T21:00:00Z")
            val job = OrderDailyJob.job(dag, LocalTime(1, 0))
            job.trigger
                .shouldBeInstanceOf<Trigger.Scheduled>()
                .schedule
                .latest(Instant.parse("2026-10-13T16:30:00Z"))
                .partition
                .value shouldBe "2026-10-13"

            metrics.ingested(IngestReport(3, 1))
            metrics.built(day, build(day).ok())
            val collected = reader.collectAllMetrics().associateBy { it.name }
            collected
                .getValue("eia.batch_etl.events.ingested")
                .longSumData.points
                .single()
                .value shouldBe 3
            collected
                .getValue("eia.batch_etl.events.dead_lettered")
                .longSumData.points
                .single()
                .value shouldBe 1
            collected
                .getValue("eia.batch_etl.partitions.rebuilt")
                .longSumData.points
                .associate {
                    it.attributes
                        .asMap()
                        .values
                        .single() to it.value
                } shouldBe mapOf(true to 1L, false to 1L)
            collected
                .getValue("eia.batch_etl.late_days.pending")
                .longGaugeData.points
                .single()
                .value shouldBe 1
        }
    })
