package io.eia.b2bgateway.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.eia.b2bgateway.adapters.archive.ArchiveKeyNames
import io.eia.b2bgateway.adapters.archive.S3OriginalArchive
import io.eia.b2bgateway.adapters.archive.S3OriginalReader
import io.eia.b2bgateway.adapters.crypto.PgpDocumentCrypto
import io.eia.b2bgateway.adapters.inbox.SftpPartnerInbox
import io.eia.b2bgateway.adapters.order.HttpOrderApi
import io.eia.b2bgateway.adapters.order.OrderSubmissionMetrics
import io.eia.b2bgateway.adapters.order.SubmissionSnapshot
import io.eia.b2bgateway.adapters.outbox.SftpPartnerOutbox
import io.eia.b2bgateway.adapters.partner.LedgerPartnerAgreements
import io.eia.b2bgateway.adapters.partner.PartnerPgpKeyExpiry
import io.eia.b2bgateway.adapters.persistence.JdbcAcknowledgements
import io.eia.b2bgateway.adapters.persistence.JdbcCodeTables
import io.eia.b2bgateway.adapters.persistence.JdbcControlReferences
import io.eia.b2bgateway.adapters.persistence.JdbcOrderSubmissions
import io.eia.b2bgateway.adapters.persistence.JdbcReceipts
import io.eia.b2bgateway.adapters.spec.YamlEdiSpecs
import io.eia.b2bgateway.application.port.inbound.AcceptDocumentUseCase
import io.eia.b2bgateway.application.port.inbound.ImportCodeTableUseCase
import io.eia.b2bgateway.application.port.inbound.PollInboxUseCase
import io.eia.b2bgateway.application.port.inbound.SendAcknowledgementsUseCase
import io.eia.b2bgateway.application.port.inbound.SubmitOrdersUseCase
import io.eia.b2bgateway.application.usecase.AcceptDocumentService
import io.eia.b2bgateway.application.usecase.AcknowledgementPorts
import io.eia.b2bgateway.application.usecase.ImportCodeTableService
import io.eia.b2bgateway.application.usecase.PollInboxService
import io.eia.b2bgateway.application.usecase.ReceiveInterchangeService
import io.eia.b2bgateway.application.usecase.ReceivingPorts
import io.eia.b2bgateway.application.usecase.SendAcknowledgementsService
import io.eia.b2bgateway.application.usecase.SubmitOrdersService
import io.eia.platform.audit.AuditMetrics
import io.eia.platform.audit.anchor.AnchorCycle
import io.eia.platform.audit.anchor.AnchorMetrics
import io.eia.platform.audit.anchor.AnchorPublisher
import io.eia.platform.audit.anchor.S3AnchorStore
import io.eia.platform.audit.anchor.S3AnchorStoreConfig
import io.eia.platform.audit.anchor.ServiceName
import io.eia.platform.audit.jdbc.AuditLog
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.filetransfer.store.S3StoreConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.ktor.client.ClientObservability
import io.eia.platform.reliability.ResilienceMetrics
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretProvider
import io.eia.platform.security.token.ClientCredentialsConfig
import io.eia.platform.security.token.ClientCredentialsTokenProvider
import io.eia.platform.tradingpartner.CredentialExpiries
import io.eia.platform.tradingpartner.CredentialExpiryMetrics
import io.eia.platform.tradingpartner.TradingPartnerLedger
import io.eia.shared.kernel.getOrElse
import io.eia.shared.kernel.getOrNull
import io.eia.shared.resilience.CircuitBreakerConfig
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.koin.core.module.Module
import org.koin.dsl.module
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** 配線に渡す、起動の前に確かめた設定。[serve] は serve のときだけ(codes では null)。 */
internal data class Inputs(
    val config: B2bGatewayConfig,
    val ledger: TradingPartnerLedger,
    val appPassword: Secret,
    val serve: B2bGatewayConfig.Serve?,
)

/** 受け取りと応答の送信の相手(台帳で EDI の文書を扱う取引先)。 */
internal class EdiPartners(
    val inbound: List<String>,
    val acknowledgements: List<String>,
) {
    companion object {
        fun of(ledger: TradingPartnerLedger): EdiPartners =
            EdiPartners(
                inbound = ledger.partners.filter { it.sftp.directories.inbound != null }.map { it.id },
                acknowledgements = ledger.partners.filter { it.sftp.directories.acknowledgements != null }.map { it.id },
            )
    }
}

/**
 * b2b-gateway の配線(Koin)。DB はアプリのロールで接続する(所有者の権限は持たない)。
 * 対応表の取り込み(codes)だけなら、DB と台帳だけで動く。受け取り・応答の送信・社内への取り込みは serve のときだけ配線する。
 */
internal fun b2bGatewayModule(
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
                poolName = "b2b-gateway"
            },
        )
    }
    single { AuditLog(listener = AuditMetrics(runtime.meter)) }
    single { inputs.ledger }
    single { LedgerPartnerAgreements.of(inputs.ledger).getOrElse { error("台帳は起動の前に検査しています: ${it.message}") } }
    single { JdbcCodeTables(get<HikariDataSource>(), get()) }
    single<ImportCodeTableUseCase> { ImportCodeTableService(get<LedgerPartnerAgreements>(), get<JdbcCodeTables>()) }
    inputs.serve?.let { serveBeans(inputs, it, secrets, runtime) }
}

@Suppress("LongMethod") // 受け取り・応答の送信・社内への取り込みの部品を、順に並べる
private fun Module.serveBeans(
    inputs: Inputs,
    serve: B2bGatewayConfig.Serve,
    secrets: SecretProvider,
    runtime: ObservabilityRuntime,
) {
    val config = inputs.config
    val partners = EdiPartners.of(inputs.ledger)
    single { partners }
    single { ResilienceMetrics(runtime.meter) }
    single {
        S3ObjectStore(
            S3StoreConfig(serve.s3Endpoint, config.bucket, B2bGatewayConfig.S3_ACCESS_KEY, B2bGatewayConfig.S3_SECRET_KEY),
            secrets,
        )
    }
    // 取引先ごとの SFTP の Resilience(`sftp-{取引先の ID}`)。受け取りと送信で共有する(ADR-0040 §4・§7)
    single<Map<String, Resilience>> {
        val metrics = get<ResilienceMetrics>()
        inputs.ledger.partners.associate { it.id to metrics.resilience("sftp-${it.id}", partnerResilience(config.sftpOpenDuration)) }
    }
    single { PgpDocumentCrypto(inputs.ledger, secrets, B2bGatewayConfig.PGP_SECRET_KEY, B2bGatewayConfig.PGP_PASSPHRASE, config.workDir) }
    single {
        S3OriginalArchive(
            get<S3ObjectStore>(),
            secrets,
            ArchiveKeyNames(B2bGatewayConfig.ARCHIVE_PGP_PUBLIC_KEY, B2bGatewayConfig.PGP_SECRET_KEY, B2bGatewayConfig.PGP_PASSPHRASE),
            serve.retention.toJavaDuration(),
            config.workDir,
        )
    }
    single { YamlEdiSpecs() }
    single { JdbcReceipts(get<HikariDataSource>(), get()) }
    single { JdbcControlReferences(get<HikariDataSource>()) }
    single {
        val receipts = get<JdbcReceipts>()
        ReceiveInterchangeService(
            get<YamlEdiSpecs>(),
            ReceivingPorts(get<LedgerPartnerAgreements>(), receipts, get<JdbcCodeTables>(), receipts, get<JdbcControlReferences>()),
            Clock.System,
        )
    }
    single<AcceptDocumentUseCase> {
        AcceptDocumentService(
            get<ReceiveInterchangeService>(),
            get<S3OriginalArchive>(),
            get<PgpDocumentCrypto>(),
            get<JdbcReceipts>(),
            Clock.System,
            config.submitWindow,
        )
    }
    single { SftpPartnerInbox(inputs.ledger, secrets, get()) }
    single<PollInboxUseCase> { PollInboxService(get<SftpPartnerInbox>(), get()) }
    single { SftpPartnerOutbox(inputs.ledger, secrets, get(), get<PgpDocumentCrypto>(), config.workDir) }
    single<SendAcknowledgementsUseCase> {
        SendAcknowledgementsService(
            JdbcAcknowledgements(get<HikariDataSource>(), get()),
            S3OriginalReader(get<S3ObjectStore>()),
            get<SftpPartnerOutbox>(),
            ::sha256,
        )
    }
    orderBeans(serve, config.clientId, secrets, runtime)
    if (config.anchor.enabled) anchorBeans(config.anchor, secrets, runtime)
    single { B2bGatewayMetrics(runtime.meter) }
    // 取引先の鍵とホスト鍵の有効期限(ADR-0040 §3)。file-exchange も同じ系列を出すので、アラートとダッシュボードは最小値で見る
    single(createdAtStart = true) {
        val expiry = PartnerPgpKeyExpiry(secrets)
        CredentialExpiryMetrics(runtime.meter) { CredentialExpiries.of(inputs.ledger, expiry) }
    }
}

/** 社内への取り込み(ADR-0040 §6)。order の API は API Gateway 経由(Client Credentials。traceparent と Correlation ID を送る)。 */
private fun Module.orderBeans(
    serve: B2bGatewayConfig.Serve,
    clientId: String,
    secrets: SecretProvider,
    runtime: ObservabilityRuntime,
) {
    single { HttpClient(CIO) { install(ClientObservability) { this.runtime = runtime } } }
    single {
        ClientCredentialsTokenProvider(
            ClientCredentialsConfig(serve.tokenUrl, clientId, B2bGatewayConfig.CLIENT_SECRET, setOf(HttpOrderApi.SCOPE)),
            get(),
            secrets,
            resilience =
                get<ResilienceMetrics>().resilience(
                    ClientCredentialsTokenProvider.DEFAULT_RESILIENCE_NAME,
                    ClientCredentialsTokenProvider.DEFAULT_RESILIENCE,
                ),
        )
    }
    single {
        HttpOrderApi(
            client = get(),
            gatewayUrl = serve.gatewayUrl,
            tokens = get(),
            resilience =
                get<ResilienceMetrics>().resilience(
                    "order-api",
                    ResilienceConfig(attemptTimeout = 10.seconds, deadline = 1.minutes),
                ),
        )
    }
    single { JdbcOrderSubmissions(get<HikariDataSource>(), get()) }
    single<SubmitOrdersUseCase> {
        SubmitOrdersService(
            get<JdbcOrderSubmissions>(),
            get<HttpOrderApi>(),
            AcknowledgementPorts(get<YamlEdiSpecs>(), get<JdbcControlReferences>(), get<PgpDocumentCrypto>(), get<S3OriginalArchive>()),
            Clock.System,
        )
    }
    // 送信待ちの期限のメトリクス。値は常駐のループが DB から読み直して入れる(読み出しのたびに DB に行かない)
    single { AtomicReference(SubmissionSnapshot(emptyMap(), emptyMap())) }
    single(createdAtStart = true) {
        val snapshot = get<AtomicReference<SubmissionSnapshot>>()
        OrderSubmissionMetrics(runtime.meter) { snapshot.get() }
    }
}

/**
 * 監査のアンカーの定期的な保存(ADR-0017 §5)。保存の前に、前回のアンカーからの差分を検証する([AnchorCycle])。
 * S3 には b2b-gateway のアンカーの書込み用の identity(`eiaf-audit-b2b-gateway`。`anchors/b2b-gateway/` の下にだけ書ける)で接続する。
 */
private fun Module.anchorBeans(
    anchor: B2bGatewayConfig.AnchorSettings,
    secrets: SecretProvider,
    runtime: ObservabilityRuntime,
) {
    val service = requireNotNull(ServiceName.parse(SERVICE_NAME).getOrNull())
    single {
        S3AnchorStore(
            S3AnchorStoreConfig(
                endpoint = requireNotNull(anchor.endpoint) { "${B2bGatewayConfig.AUDIT_S3_ENDPOINT} は起動の前に検査しています" },
                bucket = anchor.bucket,
                accessKeyName = B2bGatewayConfig.AUDIT_S3_ACCESS_KEY,
                secretKeyName = B2bGatewayConfig.AUDIT_S3_SECRET_KEY,
            ),
            secrets,
        )
    }
    single { AnchorMetrics(runtime.meter, anchor.interval.toJavaDuration()) }
    single {
        val retention = requireNotNull(anchor.retention) { "${B2bGatewayConfig.ANCHOR_RETENTION} は起動の前に検査しています" }
        AnchorCycle(
            service,
            get<S3AnchorStore>(),
            AnchorPublisher(service, get<S3AnchorStore>(), retention.toJavaDuration()),
            get<AnchorMetrics>(),
        )
    }
}

/** 監査のサービスの名前(アンカーのキー `anchors/b2b-gateway/`。`make audit-verify SERVICE=b2b-gateway`)。 */
internal const val SERVICE_NAME = "b2b-gateway"

internal fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** 接続のプール。受け取り・応答の送信・社内への取り込み・期限の読み直し・ヘルスチェックが同時に使っても収まる大きさ。 */
private const val POOL_SIZE = 8

/**
 * 取引先の SFTP の Timeout・Retry・Circuit Breaker(file-exchange と同じ。ADR-0032 §8)。試行の上限は、接続(10 秒)と応答の待ち(30 秒)に、
 * 読み書きの時間を足したもの。Open の期間は設定(`B2B_GATEWAY_SFTP_OPEN_DURATION`)。
 */
private fun partnerResilience(openDuration: Duration) =
    ResilienceConfig(attemptTimeout = 2.minutes, deadline = 5.minutes, circuitBreaker = CircuitBreakerConfig(openDuration = openDuration))
