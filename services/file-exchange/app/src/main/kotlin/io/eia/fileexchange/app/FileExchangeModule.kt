package io.eia.fileexchange.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.fileexchange.adapters.file.S3Inbox
import io.eia.fileexchange.adapters.file.SalesDailyLayout
import io.eia.fileexchange.adapters.handoff.S3Handoff
import io.eia.fileexchange.adapters.persistence.BatchFileLocks
import io.eia.fileexchange.adapters.persistence.JdbcPastDeliveries
import io.eia.fileexchange.adapters.persistence.JdbcReceivedFiles
import io.eia.fileexchange.application.port.inbound.HandOffFileUseCase
import io.eia.fileexchange.application.port.inbound.HandOffUnsentUseCase
import io.eia.fileexchange.application.port.inbound.ReceiveFileUseCase
import io.eia.fileexchange.application.port.inbound.SweepInboxUseCase
import io.eia.fileexchange.application.usecase.HandOffFileService
import io.eia.fileexchange.application.usecase.HandOffUnsentService
import io.eia.fileexchange.application.usecase.ReceiveFileService
import io.eia.fileexchange.application.usecase.SweepInboxService
import io.eia.platform.batch.BatchMetrics
import io.eia.platform.batch.BatchRunner
import io.eia.platform.batch.BatchScheduler
import io.eia.platform.batch.jdbc.JdbcBatchStore
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.filetransfer.store.S3StoreConfig
import io.eia.platform.filetransfer.verify.FileVerifier
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.jobs.RepeatingJobs
import io.eia.platform.security.secret.SecretProvider
import org.koin.dsl.module

/**
 * file-exchange の配線(Koin)。DB はアプリのロールで接続する(所有者の権限は持たない)。
 * 受け取りの実行と見回りは、ファイルの名前ごとに同じ advisory lock を使う(実行の間、接続を 1 つ持ち続ける。ADR-0030 §2)。
 */
internal fun fileExchangeModule(
    inputs: Inputs,
    secrets: SecretProvider,
    runtime: ObservabilityRuntime,
) = module {
    val config = inputs.config
    single { runtime }
    single {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.appUser
                password = inputs.appPassword.reveal()
                maximumPoolSize = POOL_SIZE
                poolName = "file-exchange"
            },
        )
    }
    single { JdbcBatchStore(get<HikariDataSource>()) }
    single {
        S3ObjectStore(
            S3StoreConfig(inputs.s3Endpoint, config.bucket, FileExchangeConfig.S3_ACCESS_KEY, FileExchangeConfig.S3_SECRET_KEY),
            secrets,
        )
    }
    single { S3Inbox(get<S3ObjectStore>(), FileVerifier(get<S3ObjectStore>(), listOf(SalesDailyLayout.V1)), config.prefixes) }
    single { JdbcReceivedFiles(get<HikariDataSource>()) }
    single<ReceiveFileUseCase> { ReceiveFileService(get<S3Inbox>(), get<JdbcReceivedFiles>()) }
    single<SweepInboxUseCase> {
        SweepInboxService(get<S3Inbox>(), get<JdbcReceivedFiles>(), BatchFileLocks(get<JdbcBatchStore>(), ReceiveJob.DAG_ID))
    }
    single { JdbcPastDeliveries(get<HikariDataSource>()) }
    single<HandOffFileUseCase> {
        HandOffFileService(get<JdbcReceivedFiles>(), S3Handoff(get<S3ObjectStore>(), config.prefixes.archive, config.outboundPrefix))
    }
    single<HandOffUnsentUseCase> {
        HandOffUnsentService(get<JdbcPastDeliveries>(), get(), BatchFileLocks(get<JdbcBatchStore>(), ReceiveJob.DAG_ID))
    }
    single { FileExchangeMetrics(runtime.meter) }
    single { ReceiveJob.dag(get(), get(), get()) }
    single { RepeatingJobs(runtime.meter) }
    single { BatchRunner(get<JdbcBatchStore>(), runtime, listener = BatchMetrics(runtime.meter)) }
    single {
        BatchScheduler(
            runner = get(),
            store = get<JdbcBatchStore>(),
            jobs = listOf(ReceiveJob.job(get(), get<S3ObjectStore>(), config.prefixes.inbox)),
            settings = BatchScheduler.Settings(pollInterval = config.pollInterval, maxConcurrentRuns = MAX_CONCURRENT_RUNS),
            repeating = get(),
        )
    }
}

/**
 * 接続のプール。受け取りの実行は 1 つで 2 本(実行のロックと、記録の読み書き)を使う。同時に動かす実行は [MAX_CONCURRENT_RUNS] までにし、
 * 見回り(ロックと読み書きの 2 本)とヘルスチェック(1 本)を足しても収まる大きさにする(3 × 2 + 2 + 1 = 9。#133)。
 */
private const val POOL_SIZE = 10

/** 同時に動かす受け取りの実行の上限(ファイルがまとめて届いても、プールを使い切らない。残りは次の確認の回に動く。#133)。 */
private const val MAX_CONCURRENT_RUNS = 3
