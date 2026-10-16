package io.eia.fileexchange.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.fileexchange.adapters.file.S3Inbox
import io.eia.fileexchange.adapters.file.SalesDailyLayout
import io.eia.fileexchange.adapters.persistence.BatchFileLocks
import io.eia.fileexchange.adapters.persistence.JdbcReceivedFiles
import io.eia.fileexchange.application.port.inbound.ReceiveFileUseCase
import io.eia.fileexchange.application.port.inbound.SweepInboxUseCase
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
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretProvider
import org.koin.dsl.module
import java.net.URI

/**
 * file-exchange の配線(Koin)。DB はアプリのロールで接続する(所有者の権限は持たない)。
 * 受け取りの実行と見回りは、ファイルの名前ごとに同じ advisory lock を使う(実行の間、接続を 1 つ持ち続ける。ADR-0030 §2)。
 */
internal fun fileExchangeModule(
    config: FileExchangeConfig,
    s3Endpoint: URI,
    appPassword: Secret,
    secrets: SecretProvider,
    runtime: ObservabilityRuntime,
) = module {
    single { runtime }
    single {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.dbUrl
                username = config.appUser
                password = appPassword.reveal()
                maximumPoolSize = POOL_SIZE
                poolName = "file-exchange"
            },
        )
    }
    single { JdbcBatchStore(get<HikariDataSource>()) }
    single {
        S3ObjectStore(
            S3StoreConfig(s3Endpoint, config.bucket, FileExchangeConfig.S3_ACCESS_KEY, FileExchangeConfig.S3_SECRET_KEY),
            secrets,
        )
    }
    single { S3Inbox(get<S3ObjectStore>(), FileVerifier(get<S3ObjectStore>(), listOf(SalesDailyLayout.V1)), config.prefixes) }
    single { JdbcReceivedFiles(get<HikariDataSource>()) }
    single<ReceiveFileUseCase> { ReceiveFileService(get<S3Inbox>(), get<JdbcReceivedFiles>()) }
    single<SweepInboxUseCase> {
        SweepInboxService(get<S3Inbox>(), get<JdbcReceivedFiles>(), BatchFileLocks(get<JdbcBatchStore>(), ReceiveJob.DAG_ID))
    }
    single { FileExchangeMetrics(runtime.meter) }
    single { ReceiveJob.dag(get(), get()) }
    single { BatchRunner(get<JdbcBatchStore>(), runtime, listener = BatchMetrics(runtime.meter)) }
    single {
        BatchScheduler(
            runner = get(),
            store = get<JdbcBatchStore>(),
            jobs = listOf(ReceiveJob.job(get(), get<S3ObjectStore>(), config.prefixes.inbox)),
            settings = BatchScheduler.Settings(pollInterval = config.pollInterval),
        )
    }
}

private const val POOL_SIZE = 5
