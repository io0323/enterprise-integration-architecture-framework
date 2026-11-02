package io.eia.b2bgateway.app

import com.zaxxer.hikari.HikariDataSource
import io.eia.b2bgateway.adapters.order.SubmissionSnapshot
import io.eia.b2bgateway.adapters.persistence.JdbcOrderSubmissions
import io.eia.b2bgateway.application.port.inbound.PollInboxUseCase
import io.eia.b2bgateway.application.port.inbound.SendAcknowledgementsUseCase
import io.eia.b2bgateway.application.port.inbound.SubmitOrdersUseCase
import io.eia.platform.audit.anchor.AnchorCycle
import io.eia.platform.audit.anchor.AnchorOutcome
import io.eia.platform.filetransfer.store.S3ObjectStore
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.eia.shared.kernel.ok
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * b2b-gateway の常駐のプロセス(ADR-0040)。次の 4 つを、それぞれの間隔で繰り返す。
 * 1. 取引先の受け取りのディレクトリの見回り(書き終わったファイルを押さえて受け付ける。ADR-0040 §4)
 * 2. 送信待ちの応答(CONTRL・APERAK)の送信(ADR-0040 §7)
 * 3. 受け付けた注文の、order の API への送信(ADR-0040 §6)
 * 4. 送信待ちの期限のメトリクスの読み直し
 *
 * - 1 つの取引先・1 回の処理の失敗は、ほかを止めない(ログとメトリクスに出し、次の回にやり直す)。
 * - 止める(SIGTERM)と、処理中のファイルは処理中のディレクトリに残り、次の起動で続きから処理する。
 * - ヘルスチェックのポートは平文で `/health/live` と `/health/ready` だけ。ready は、DB に接続でき、繰り返しを始めているときだけ UP。
 */
internal class B2bGatewayServer private constructor(
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    private val koin: Koin,
    private val runtime: ObservabilityRuntime,
    private val scope: CoroutineScope,
) {
    private val stopped = CountDownLatch(1)

    val healthPort: Int get() =
        runBlocking {
            server.engine
                .resolvedConnectors()
                .first()
                .port
        }

    fun awaitTermination() {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        stopped.await()
    }

    fun stop() {
        if (stopped.count == 0L) return
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        close(koin, runtime)
        stopped.countDown()
    }

    companion object {
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private const val VALID_TIMEOUT_SECONDS = 2
        private val logger = LoggerFactory.getLogger(B2bGatewayServer::class.java)

        /** 設定・台帳・アプリのロールのパスワード・OTel を用意して、配線を作る。[serve] なら受け取り・送信・取り込みも配線する。 */
        internal fun wire(
            env: Map<String, String>,
            serve: Boolean,
            serviceName: String = "b2b-gateway",
        ): Result<Pair<B2bGatewayConfig, Pair<Koin, ObservabilityRuntime>>, ValidationError> =
            B2bGatewayConfig
                .fromEnvironment(env)
                .flatMap { config -> (if (serve) config.forServe().map { it } else ok(null)).map { config to it } }
                .flatMap { (config, forServe) -> config.ledger().map { ledger -> Triple(config, forServe, ledger) } }
                .flatMap { (config, forServe, ledger) ->
                    when (val password = EnvSecretProvider(env).get(B2bGatewayConfig.APP_PASSWORD)) {
                        is Result.Ok -> ok(Inputs(config, ledger, password.value, forServe))
                        is Result.Err -> err(ValidationError.of(B2bGatewayConfig.APP_PASSWORD.value, password.error.message))
                    }
                }.flatMap { inputs ->
                    val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to serviceName) + env
                    ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability -> inputs to observability }
                }.map { (inputs, observability) ->
                    val runtime = Observability.init(observability)
                    val koin = koinApplication { modules(b2bGatewayModule(inputs, EnvSecretProvider(env), runtime)) }.koin
                    inputs.config to (koin to runtime)
                }

        internal fun close(
            koin: Koin,
            runtime: ObservabilityRuntime,
        ) {
            koin.getOrNull<HttpClient>()?.close()
            koin.getOrNull<S3ObjectStore>()?.close()
            koin.get<HikariDataSource>().close()
            koin.close()
            runtime.close()
        }

        fun start(env: Map<String, String>): Result<B2bGatewayServer, ValidationError> =
            wire(env, serve = true).map { (config, wired) ->
                val (koin, runtime) = wired
                val dataSource = koin.get<HikariDataSource>()
                val running = AtomicBoolean(false)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val intervals = config.intervals
                scope.launch {
                    running.set(true)
                    repeatEvery(intervals.poll) { pollOnce(koin) }
                }
                scope.launch { repeatEvery(intervals.send) { sendOnce(koin) } }
                scope.launch { repeatEvery(intervals.submit) { submitOnce(koin) } }
                scope.launch { repeatEvery(intervals.metrics) { refreshSnapshot(koin) } }
                val anchorCycle = koin.getOrNull<AnchorCycle>()
                if (anchorCycle != null) {
                    scope.launch { repeatEvery(config.anchor.interval) { anchorOnce(anchorCycle, dataSource) } }
                } else {
                    logger.warn("監査のアンカーの保存は無効です({}=false)。末尾の記録の改竄と削除は検出できません", B2bGatewayConfig.ANCHOR_ENABLED)
                }
                val server =
                    embeddedServer(Netty, configure = { connector { port = config.healthPort } }) {
                        routing {
                            get("/health/live") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
                            get("/health/ready") {
                                if (running.get() && databaseReachable(dataSource)) {
                                    call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
                                } else {
                                    call.respondText(
                                        """{"status":"DOWN"}""",
                                        ContentType.Application.Json,
                                        HttpStatusCode.ServiceUnavailable,
                                    )
                                }
                            }
                        }
                    }.start(wait = false)
                logger.info("b2b-gateway を起動しました(取引先 {} 件)", koin.get<EdiPartners>().inbound.size)
                B2bGatewayServer(server, koin, runtime, scope)
            }

        private suspend fun repeatEvery(
            interval: Duration,
            block: suspend () -> Unit,
        ) {
            while (currentCoroutineContext().isActive) {
                block()
                delay(interval)
            }
        }

        /** 取引先ごとに受け取りのディレクトリを 1 回見回る。 */
        internal suspend fun pollOnce(koin: Koin) {
            val poll = koin.get<PollInboxUseCase>()
            val metrics = koin.get<B2bGatewayMetrics>()
            koin.get<EdiPartners>().inbound.forEach { partnerId ->
                when (val summary = poll(partnerId)) {
                    is Result.Ok -> {
                        metrics.polled(partnerId, summary.value)
                        summary.value.failed.forEach { logger.warn("取引先 {} のファイル {} を受け付けられません({})", partnerId, it.name, it.error.code) }
                    }

                    is Result.Err -> {
                        metrics.pollFailed(partnerId)
                        logger.warn("取引先 {} の受け取りのディレクトリを見られません({})", partnerId, summary.error.code)
                    }
                }
            }
        }

        /** 取引先ごとに送信待ちの応答を 1 回送る。 */
        internal suspend fun sendOnce(koin: Koin) {
            val send = koin.get<SendAcknowledgementsUseCase>()
            val metrics = koin.get<B2bGatewayMetrics>()
            koin.get<EdiPartners>().acknowledgements.forEach { partnerId ->
                when (val summary = send(partnerId)) {
                    is Result.Ok -> {
                        metrics.sent(partnerId, summary.value)
                        summary.value.failed.forEach { logger.warn("取引先 {} に応答 {} を送れません({})", partnerId, it.id, it.error.code) }
                    }

                    is Result.Err -> {
                        logger.warn("取引先 {} の送信待ちの応答を読めません({})", partnerId, summary.error.code)
                    }
                }
            }
        }

        /** 受け付けた注文を 1 回送る。 */
        internal suspend fun submitOnce(koin: Koin) {
            when (val summary = koin.get<SubmitOrdersUseCase>()()) {
                is Result.Ok -> {
                    koin.get<B2bGatewayMetrics>().submitted(summary.value)
                    summary.value.failed.forEach { logger.warn("注文 {} を社内に取り込めません({})", it.id, it.error.code) }
                    summary.value.expired.forEach { logger.error("注文 {} は送り直しの上限を過ぎました(手の対応が要ります)", it) }
                }

                is Result.Err -> {
                    logger.warn("送信待ちの注文を読めません({})", summary.error.code)
                }
            }
        }

        /** 送信待ちの期限のメトリクスの元を DB から読み直す。 */
        internal suspend fun refreshSnapshot(koin: Koin) {
            when (val snapshot = koin.get<JdbcOrderSubmissions>().snapshot()) {
                is Result.Ok -> koin.get<AtomicReference<SubmissionSnapshot>>().set(snapshot.value)
                is Result.Err -> logger.warn("送信待ちの期限を読めません({})", snapshot.error.code)
            }
        }

        /**
         * 監査のアンカーの検査と保存(ADR-0017 §5)。改竄の疑いがあれば保存せず ERROR を残す(対応は docs/runbooks/audit-verify.md)。
         * ストレージや DB に届かなければ次の回でやり直す。成否はメトリクス(AnchorMetrics)で監視する。
         */
        @Suppress("TooGenericExceptionCaught") // DB の接続の失敗はどの種類でも次の回にやり直す
        internal suspend fun anchorOnce(
            cycle: AnchorCycle,
            dataSource: HikariDataSource,
        ) {
            val result =
                try {
                    withContext(Dispatchers.IO) { dataSource.connection.use { cycle.run(it) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("監査のアンカーの検査を終えられませんでした(error.type={})", e::class.qualifiedName)
                    return
                }
            when (result) {
                is Result.Err -> {
                    logger.warn("監査のアンカーの検査を終えられませんでした。次の回でやり直します(error.code={})", result.error.code)
                }

                is Result.Ok -> {
                    when (val outcome = result.value) {
                        is AnchorOutcome.Published -> {
                            logger.info("監査のアンカーを保存しました(key={}, seq={})", outcome.anchor.key, outcome.anchor.anchor.seq)
                        }

                        is AnchorOutcome.Rejected -> {
                            outcome.findings.forEach { logger.error("監査記録に改竄の疑いがあります({}): {}", it.code, it.describe()) }
                            logger.error("改竄の疑いがあるため、監査のアンカーを保存しません(対応: docs/runbooks/audit-verify.md)")
                        }

                        else -> {
                            logger.info("監査のアンカーの検査を終えました({})", outcome::class.simpleName)
                        }
                    }
                }
            }
        }

        @Suppress("TooGenericExceptionCaught") // 接続の失敗はどの種類でも DOWN にする
        private fun databaseReachable(dataSource: HikariDataSource): Boolean =
            try {
                dataSource.connection.use { it.isValid(VALID_TIMEOUT_SECONDS) }
            } catch (_: Exception) {
                false
            }
    }
}
