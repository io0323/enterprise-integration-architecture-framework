package io.eia.order.app

import com.zaxxer.hikari.HikariDataSource
import io.eia.order.adapters.inbound.rest.OrderProblems
import io.eia.order.adapters.inbound.rest.orderRoutes
import io.eia.order.application.port.inbound.TimeoutSagasUseCase
import io.eia.platform.api.deadline.installRequestDeadline
import io.eia.platform.api.idempotency.IdempotencyStore
import io.eia.platform.api.problem.Problem
import io.eia.platform.api.problem.ProblemType
import io.eia.platform.api.problem.installProblemDetails
import io.eia.platform.api.problem.respondProblem
import io.eia.platform.audit.AuditError
import io.eia.platform.audit.anchor.AnchorCycle
import io.eia.platform.audit.anchor.AnchorOutcome
import io.eia.platform.audit.anchor.S3AnchorStore
import io.eia.platform.inbox.Inbox
import io.eia.platform.messagingkafka.EventConsumer
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.observability.ktor.server.ServerObservability
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.security.jwt.JwtVerifier
import io.eia.platform.security.jwt.prefetchUntilLoaded
import io.eia.platform.security.ktor.eiaJwt
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.platform.security.secret.Secret
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.producer.Producer
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration

/**
 * order-service の HTTP サーバ(serve。Ktor の Netty。ADR-0024 §1)。
 *
 * プラグインの順序: `ServerObservability`(Monitoring)→ リクエストの予算(`installRequestDeadline`)→ Problem Details → 認証 → ルート。
 *
 * コネクタは 2 つ(ADR-0024 §6):
 * - API のポート([OrderConfig.httpsPort]): mTLS だけで受ける。クライアント証明書は必須で、開発用 CA の署名と、SAN の許可の一覧
 *   ([ClientCertificateAllowList])を確かめる。API のルートはこのポートにだけある。
 * - ヘルスチェックのポート([OrderConfig.healthPort]): 平文で、`/health/live` と `/health/ready` だけを返す(認証しない)。
 *   コンテナのヘルスチェック用で、コンテナの外には公開しない。
 */
internal class OrderServer private constructor(
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val koin: Koin,
    private val runtime: ObservabilityRuntime,
    private val sagaReplies: Job? = null,
    private val sagaThread: ExecutorService? = null,
) {
    private val stopped = CountDownLatch(1)

    /** API のポート(mTLS。`ORDER_HTTPS_PORT=0` のときは割り当てられたポート)。 */
    val httpsPort: Int get() = resolvedPort(ConnectorType.HTTPS)

    /** ヘルスチェックのポート(平文。`ORDER_HEALTH_PORT=0` のときは割り当てられたポート)。 */
    val healthPort: Int get() = resolvedPort(ConnectorType.HTTP)

    private fun resolvedPort(type: ConnectorType): Int =
        runBlocking {
            server.engine
                .resolvedConnectors()
                .first { it.type == type }
                .port
        }

    fun awaitTermination() {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        stopped.await()
    }

    fun stop() {
        if (stopped.count == 0L) return
        // 返信の読み取りを止める(Consumer は読み取りのスレッドで閉じる)。コミットしていない返信は、次の起動で読み直す
        runBlocking { sagaReplies?.cancelAndJoin() }
        sagaThread?.shutdown()
        koin.getOrNull<Producer<ByteArray, ByteArray>>()?.close()
        server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        koin.get<JwtVerifier>().close()
        koin.get<HttpClient>(SCHEMA_REGISTRY_HTTP).close()
        koin.getOrNull<S3AnchorStore>()?.close()
        koin.get<HikariDataSource>().close()
        koin.close()
        runtime.close()
        stopped.countDown()
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private const val READY_TIMEOUT_SECONDS = 2

        /** Framework 12.2: TLS 1.2 以上。 */
        private val TLS_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")
        private val logger = LoggerFactory.getLogger(OrderServer::class.java)

        /**
         * 設定を読み、サーバを起動する。所有者のパスワード(`ORDER_DB_PASSWORD` / `ORDER_DB_PASSWORD_FILE`)が環境にあれば起動しない
         * (serve のプロセスは所有者の権限を持たない。ADR-0024 §2)。
         */
        fun start(env: Map<String, String>): Result<OrderServer, ValidationError> =
            serveInputs(env).flatMap { (config, appPassword) ->
                val secrets = EnvSecretProvider(env)
                ServerTls.load(config.tls).flatMap { tls ->
                    val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to "order-service") + env
                    ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability ->
                        val runtime = Observability.init(observability)
                        val koin = koinApplication { modules(orderModule(config, appPassword, runtime, secrets)) }.koin
                        val server =
                            embeddedServer(Netty, configure = { connectors(config, tls) }) { orderApplication(koin, config) }
                                .start(wait = false)
                        val (replies, thread) = startSagaReplies(koin)
                        logger.info("order-service を起動しました")
                        OrderServer(server, koin, runtime, replies, thread)
                    }
                }
            }

        /** API のポート(mTLS)とヘルスチェックのポート(平文)。ADR-0024 §6。 */
        private fun NettyApplicationEngine.Configuration.connectors(
            config: OrderConfig,
            tls: ServerTls,
        ) {
            connector { port = config.healthPort }
            sslConnector(tls.keyStore, ServerTls.KEY_ALIAS, tls::password, tls::password) {
                port = config.httpsPort
                // trustStore を設定すると、Ktor はクライアント証明書を必須にする(needClientAuth)
                trustStore = tls.trustStore
                enabledProtocols = TLS_PROTOCOLS
            }
            // 許可の一覧の確認は HTTP/1.1 のパイプラインに置く(ゲートウェイとの間は HTTP/1.1)
            enableHttp2 = false
            channelPipelineConfig = {
                if (get("ssl") != null) addAfter("ssl", ClientCertificateAllowList.NAME, ClientCertificateAllowList(config.allowedClients))
            }
        }

        /** serve の設定とアプリのロールのパスワード。所有者のパスワードがあれば、設定の誤りにする。 */
        private fun serveInputs(env: Map<String, String>): Result<Pair<OrderConfig, Secret>, ValidationError> {
            val forbidden = OrderCommands.FORBIDDEN_FOR_SERVE.filter { env.containsKey(it) }
            val violations =
                forbidden.map { FieldViolation(it, "serve には所有者のパスワードを渡さないでください(migrate にだけ渡す)") } +
                    listOf("OIDC_ISSUER", "OIDC_JWKS_URI", OrderConfig.SCHEMA_REGISTRY_URL)
                        .filter { env[it].isNullOrBlank() }
                        .map { FieldViolation(it, "serve には必須です") }
            return when {
                violations.isNotEmpty() -> {
                    err(ValidationError(violations))
                }

                else -> {
                    OrderConfig.fromEnvironment(env).flatMap { config ->
                        anchorInputs(config, env)
                            .flatMap { appPassword(env) }
                            .map { password -> config to password }
                    }
                }
            }
        }

        /** 監査のアンカーの保存が有効なら、S3 の接続先・保持期間・資格情報がそろっていること。 */
        private fun anchorInputs(
            config: OrderConfig,
            env: Map<String, String>,
        ): Result<Unit, ValidationError> {
            val violations = config.anchor.serveViolations(EnvSecretProvider(env))
            return if (violations.isEmpty()) ok(Unit) else err(ValidationError(violations))
        }

        private fun appPassword(env: Map<String, String>): Result<Secret, ValidationError> =
            when (val secret = EnvSecretProvider(env).get(OrderConfig.APP_PASSWORD)) {
                is Result.Ok -> ok(secret.value)
                is Result.Err -> err(ValidationError.of(OrderConfig.APP_PASSWORD.value, secret.error.message))
            }

        private fun Application.orderApplication(
            koin: Koin,
            config: OrderConfig,
        ) {
            install(ServerObservability) {
                runtime = koin.get()
                integrationId = "INT-SALES-001"
            }
            installRequestDeadline(config.requestBudget)
            installProblemDetails { mapper = OrderProblems::mapper }
            install(Authentication) {
                eiaJwt {
                    verifier = koin.get()
                    realm = "eiaf"
                }
            }
            routing {
                overPlaintext { healthRoutes(koin.get(), koin.get(), koin.get()) }
                overTls { authenticate { orderRoutes(koin.get()) } }
            }
            launchPurgeJob(koin.get(), koin.get(), config)
            launchSagaTimeoutJob(koin.get(), config)
            // 書き込むイベントのスキーマ ID を解決する。解決するまで /health/ready は 503(ADR-0025 §3)
            val schemaIds = koin.get<SchemaIdBook>()
            launch { schemaIds.resolveUntilReady() }
            // アクセストークンの検証に使う JWKS を先に取得する。取得するまで /health/ready は 503(#74。ADR-0019 改訂履歴)
            launch { koin.get<JwtVerifier>().prefetchUntilLoaded() }
            val anchorCycle = koin.getOrNull<AnchorCycle>()
            if (anchorCycle != null) {
                launchAnchorJob(anchorCycle, koin.get(), config.anchor.interval)
            } else {
                logger.warn("監査のアンカーの保存は無効です({}=false)。末尾の記録の改竄と削除は検出できません", AuditAnchorConfig.ENABLED)
            }
        }

        /**
         * `/health/ready` は、DB に接続できて、書き込むイベントのスキーマ ID をすべて解決し終え、JWKS を取得できたときだけ UP
         * (ADR-0025 §3。リクエストの処理中はレジストリに問い合わせないので、解決する前はトラフィックを受けない)。
         */
        private fun Route.healthRoutes(
            dataSource: HikariDataSource,
            schemaIds: SchemaIdBook,
            verifier: JwtVerifier,
        ) {
            get("/health/live") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
            get("/health/ready") {
                // 返信の読み取り(Saga)の止まりは API の ready に含めない。Schema Registry が止まっても注文は受け付ける(ADR-0025 §3)ので、
                // 返信の読み取りだけが止まっているときに API のトラフィックを外さない。止まりは eia.consumer.unavailable と
                // EventConsumerUnavailable のアラートで分かる(ADR-0029 §3)
                val ready =
                    schemaIds.isReady &&
                        verifier.isReady &&
                        withContext(Dispatchers.IO) {
                            try {
                                dataSource.connection.use { it.isValid(READY_TIMEOUT_SECONDS) }
                            } catch (_: SQLException) {
                                false
                            }
                        }
                if (ready) {
                    call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
                } else {
                    call.respondProblem(Problem(ProblemType.SERVICE_UNAVAILABLE))
                }
            }
        }

        /**
         * 監査のアンカーの検査と保存を、起動の直後と [interval] ごとに行う(ADR-0017 §5)。サーバの停止で止まる。
         * 改竄の疑いがあれば保存せず ERROR を残す(対応は docs/runbooks/audit-verify.md)。ストレージや DB に届かなければ次の回でやり直す。
         * 成否はメトリクス(AnchorMetrics)で監視する。
         */
        private fun Application.launchAnchorJob(
            cycle: AnchorCycle,
            dataSource: HikariDataSource,
            interval: Duration,
        ) {
            launch {
                while (isActive) {
                    try {
                        val result = withContext(Dispatchers.IO) { dataSource.connection.use { cycle.run(it) } }
                        logAnchorResult(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        logger.warn("監査のアンカーの検査を終えられませんでした(error.type={})", e::class.qualifiedName)
                    }
                    delay(interval)
                }
            }
        }

        /**
         * 期限切れの Saga を、[OrderConfig.sagaScanInterval] ごとに進める(ADR-0029 §6。期限の判定は DB の時計)。サーバの停止で止まる。
         * 複数のインスタンスでも、`FOR UPDATE SKIP LOCKED` で同じ Saga を二重に処理しない。
         */
        private fun Application.launchSagaTimeoutJob(
            timeouts: TimeoutSagasUseCase,
            config: OrderConfig,
        ) {
            launch {
                while (isActive) {
                    delay(config.sagaScanInterval)
                    when (val handled = timeouts()) {
                        is Result.Ok -> if (handled.value > 0) logger.info("期限切れの Saga を {} 件進めました", handled.value)
                        is Result.Err -> logger.warn("期限切れの Saga を進められません({})。次の間隔でやり直します", handled.error.code)
                    }
                }
            }
        }

        /** 期限切れの冪等の記録(API の冪等・返信の冪等消費)を、[OrderConfig.purgeInterval] ごとに消す(ADR-0022 §3・ADR-0028 §3)。サーバの停止で止まる。 */
        private fun Application.launchPurgeJob(
            store: IdempotencyStore,
            dataSource: HikariDataSource,
            config: OrderConfig,
        ) {
            launch {
                while (isActive) {
                    delay(config.purgeInterval)
                    try {
                        val purged = store.purgeExpired(inProgressGrace = config.idempotencyLease)
                        if (purged > 0) logger.info("期限切れの冪等の記録を {} 件消しました", purged)
                        val replies = withContext(Dispatchers.IO) { purgeProcessedReplies(dataSource) }
                        if (replies > 0) logger.info("保持期間を過ぎた返信の冪等消費の記録を {} 件消しました", replies)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        // 次の周期でやり直す。例外のメッセージは SQL や接続先を含みうるため、型の名前だけを残す
                        logger.warn("期限切れの冪等の記録を消せませんでした(error.type={})", e::class.qualifiedName)
                    }
                }
            }
        }
    }
}

/** 返信の冪等消費の記録(保持期間 14 日。ADR-0028 §3)を、なくなるまで少しずつ消す。消した件数を返す。 */
private fun purgeProcessedReplies(dataSource: HikariDataSource): Int = dataSource.connection.use { purgeProcessedReplies(Inbox(), it) }

private fun purgeProcessedReplies(
    inbox: Inbox,
    connection: Connection,
): Int {
    var total = 0
    do {
        // 失敗すれば 0 件として止める(次の周期でやり直す)
        val deleted = (inbox.purgeExpired(connection) as? Result.Ok)?.value ?: 0
        total += deleted
    } while (deleted >= Inbox.DEFAULT_BATCH_SIZE)
    return total
}

/**
 * 注文 Saga の返信の読み取り(`order.saga`。ADR-0029)を、1 つのスレッドで始める(Kafka の Consumer はスレッドセーフでない)。
 * スキーマ ID を解決してから読み始める。`ORDER_KAFKA_BOOTSTRAP` がなければ読まない(Saga は期限切れの補償だけで進む)。
 */
private fun startSagaReplies(koin: Koin): Pair<Job?, ExecutorService?> {
    val consumer =
        koin.getOrNull<EventConsumer>()
            ?: return (null to null).also {
                LoggerFactory
                    .getLogger(
                        OrderServer::class.java,
                    ).warn("注文 Saga の返信を読みません(ORDER_KAFKA_BOOTSTRAP がない)")
            }
    val schemaIds = koin.get<SchemaIdBook>()
    val thread = Executors.newSingleThreadExecutor { Thread(it, "order-saga-replies") }
    val job =
        CoroutineScope(SupervisorJob() + thread.asCoroutineDispatcher()).launch {
            schemaIds.resolveUntilReady()
            consumer.run()
        }
    return job to thread
}

private fun logAnchorResult(result: Result<AnchorOutcome, AuditError>) {
    when (result) {
        is Result.Err -> {
            serverLogger.warn("監査のアンカーの検査を終えられませんでした。次の回でやり直します(error.code={}): {}", result.error.code, result.error.message)
        }

        is Result.Ok -> {
            when (val outcome = result.value) {
                is AnchorOutcome.Published -> {
                    serverLogger.info(
                        "監査のアンカーを保存しました(key={}, seq={}, 検証した記録 {} 件)",
                        outcome.anchor.key,
                        outcome.anchor.anchor.seq,
                        outcome.verifiedRecords,
                    )
                }

                is AnchorOutcome.Rejected -> {
                    // seq とアンカーのキーだけを出す(記録の中身は出さない)
                    outcome.findings.forEach { serverLogger.error("監査記録に改竄の疑いがあります({}): {}", it.code, it.describe()) }
                    serverLogger.error("改竄の疑いがあるため、監査のアンカーを保存しません(対応: docs/runbooks/audit-verify.md)")
                }

                AnchorOutcome.Empty, is AnchorOutcome.Unchanged -> {
                    serverLogger.debug("監査の記録は前回のアンカーから増えていません({})", outcome.label)
                }
            }
        }
    }
}

private val serverLogger = LoggerFactory.getLogger(OrderServer::class.java)
