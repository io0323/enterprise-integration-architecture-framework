package io.eia.fileexchange.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.fileexchange.adapters.file.S3Inbox
import io.eia.fileexchange.adapters.file.SalesDailyLayout
import io.eia.fileexchange.adapters.partner.OwnPgpKey
import io.eia.fileexchange.adapters.partner.SftpPartnerOutbox
import io.eia.fileexchange.adapters.persistence.BatchFileLocks
import io.eia.fileexchange.adapters.persistence.JdbcDeliveries
import io.eia.fileexchange.adapters.persistence.JdbcReceivedFiles
import io.eia.fileexchange.application.port.inbound.DeliverFileUseCase
import io.eia.fileexchange.application.port.inbound.ReceiveFileUseCase
import io.eia.fileexchange.application.port.inbound.SweepInboxUseCase
import io.eia.fileexchange.application.port.outbound.PartnerOutbox
import io.eia.fileexchange.application.usecase.DeliverFileService
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
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.security.secret.SecretProvider
import io.eia.shared.resilience.CircuitBreakerConfig
import io.eia.shared.resilience.ResilienceConfig
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
    deliveryBeans(inputs, secrets, runtime)
    single { FileExchangeMetrics(runtime.meter) }
    single { ReceiveJob.dag(get(), get(), get(), deliverRetryDelay = config.deliverRetryDelay) }
    single { BatchRunner(get<JdbcBatchStore>(), runtime, listener = BatchMetrics(runtime.meter)) }
    single {
        BatchScheduler(
            runner = get(),
            store = get<JdbcBatchStore>(),
            jobs = listOf(ReceiveJob.job(get(), get<S3ObjectStore>(), config.prefixes.inbox)),
            settings = BatchScheduler.Settings(pollInterval = config.pollInterval, maxConcurrentRuns = MAX_CONCURRENT_RUNS),
        )
    }
}

/**
 * 取引先への配送(ADR-0032 §8)。SFTP の呼び出しには、取引先ごとの Resilience(`sftp-{取引先の ID}`)を
 * [ResilienceMetrics] で作って付ける(#8-c。Konsist の resilienceOnlyThroughMetrics)。
 */
private fun Module.deliveryBeans(
    inputs: Inputs,
    secrets: SecretProvider,
    runtime: ObservabilityRuntime,
) {
    single { ResilienceMetrics(runtime.meter) }
    single { JdbcDeliveries(get<HikariDataSource>()) }
    single<PartnerOutbox> {
        val metrics = get<ResilienceMetrics>()
        SftpPartnerOutbox(
            registry = inputs.partners,
            store = get<S3ObjectStore>(),
            archivePrefix = inputs.config.prefixes.archive,
            ownPgpKey = OwnPgpKey(FileExchangeConfig.PGP_SECRET_KEY, FileExchangeConfig.PGP_PASSPHRASE),
            secrets = secrets,
            resilience =
                inputs.partners.partners.associate {
                    it.id to metrics.resilience("sftp-${it.id}", partnerResilience(inputs.config.sftpOpenDuration))
                },
        )
    }
    single<DeliverFileUseCase> { DeliverFileService(get<JdbcReceivedFiles>(), inputs.partners, get<JdbcDeliveries>(), get()) }
}

/**
 * 接続のプール。受け取りの実行は 1 つで 2 本(実行のロックと、記録の読み書き)を使う。同時に動かす実行は [MAX_CONCURRENT_RUNS] までにし、
 * 見回り(ロックと読み書きの 2 本)とヘルスチェック(1 本)を足しても収まる大きさにする(3 × 2 + 2 + 1 = 9。#133)。
 */
private const val POOL_SIZE = 10

/** 同時に動かす受け取りの実行の上限(ファイルがまとめて届いても、プールを使い切らない。残りは次の確認の回に動く。#133)。 */
private const val MAX_CONCURRENT_RUNS = 3

/**
 * 取引先の SFTP の Timeout・Retry・Circuit Breaker。試行の上限は、接続(10 秒)と応答の待ち(30 秒)の上限に、送る時間を足したもの。
 * Retry と Circuit Breaker の判定は既定(直近 20 回のうち 10 回以上で 50% の失敗で Open。リトライは 3 回まで)。
 * Open の期間は設定(`FILE_EXCHANGE_SFTP_OPEN_DURATION`。既定 30 秒)。
 */
private fun partnerResilience(openDuration: Duration) =
    ResilienceConfig(attemptTimeout = 2.minutes, deadline = 5.minutes, circuitBreaker = CircuitBreakerConfig(openDuration = openDuration))
