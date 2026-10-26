package io.eia.iotbridge.app

import io.eia.iotbridge.adapters.inbound.DevicePayloadDecoder
import io.eia.iotbridge.adapters.inbound.HiveMqTelemetrySubscriber
import io.eia.iotbridge.adapters.inbound.MqttSubscriberSettings
import io.eia.iotbridge.adapters.inbound.TelemetryMessageHandler
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.observability.ObservabilityRuntime
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.platform.security.secret.EnvSecretProvider
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.flatMap
import io.eia.shared.kernel.map
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
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
import org.apache.kafka.clients.producer.Producer
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * iot-bridge のプロセス(serve。ADR-0039)。
 *
 * - 起動の順: 書くイベントのスキーマ ID をすべて解決し(ADR-0025 §3)、書くトピック(テレメトリと DLQ)のメタデータを Kafka から取れることを
 *   確かめてから、MQTT を購読する。購読の前は、Broker がブリッジの永続セッションにメッセージを溜めている(§9)。
 * - 最初の接続か購読に失敗したら(Broker がまだ動いていないなど)、[MQTT_RETRY_INTERVAL] の後に新しいクライアントでやり直す。
 *   つながった後に切れたら、クライアントが自動で再接続する(セッションは Broker に残る)。
 * - ヘルスチェックのポートは平文で `/health/live` と `/health/ready` だけ。**ready の条件**(すべてを満たすときだけ UP):
 *   1. スキーマ ID をすべて解決した(ADR-0025 §3。レジストリが止まっていても起動して解決を繰り返す)
 *   2. 書くトピックのメタデータを取れた(Kafka に送れる)
 *   3. MQTT の Broker につながって購読している
 *   4. 直近の処理が一時的な失敗(Kafka に送れず PUBACK を返さずに送り直している)でない
 *   ready が DOWN の間も、受け取ったメッセージは失わない(PUBACK を返さないので Broker が持っている)。ready はトラフィックの振り分けには
 *   使わず(MQTT の購読は 1 台。§6)、監視と起動の順(compose の depends_on)に使う。
 */
internal class IotBridgeServer private constructor(
    private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
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

    /** 購読を止め(処理中の 1 件は PUBACK を返さずに終わる。Broker が送り直す)、Producer・サーバを閉じる。 */
    fun stop() {
        if (stopped.count == 0L) return
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        koin.get<Producer<ByteArray, ByteArray>>().close()
        koin.get<HttpClient>().close()
        koin.close()
        runtime.close()
        stopped.countDown()
    }

    companion object {
        /** 最初の接続か購読に失敗したときに、やり直すまでの待ち時間。 */
        val MQTT_RETRY_INTERVAL: Duration = 5.seconds
        private const val GRACE_MILLIS = 1_000L
        private const val TIMEOUT_MILLIS = 5_000L
        private const val CLIENT_ID = "iot-bridge"
        private val logger = LoggerFactory.getLogger(IotBridgeServer::class.java)

        /**
         * @param decoder 受け付ける形式の版(統合テストで、デコーダを足す前を表すときだけ渡す)
         */
        fun start(
            env: Map<String, String>,
            decoder: DevicePayloadDecoder = DevicePayloadDecoder(),
        ): Result<IotBridgeServer, ValidationError> =
            serveInputs(env)
                .flatMap { (config, settings) ->
                    val observabilityEnv = mapOf(ObservabilityConfig.ENV_SERVICE_NAME to CLIENT_ID) + env
                    ObservabilityConfig.fromEnvironment(observabilityEnv).map { observability -> Triple(config, settings, observability) }
                }.map { (config, settings, observability) ->
                    val runtime = Observability.init(observability)
                    val koin = koinApplication { modules(iotBridgeModule(config, runtime, CLIENT_ID, decoder)) }.koin
                    val schemaIds = koin.get<SchemaIdBook>()
                    val topics = koin.get<KafkaTopics>()
                    val handler = koin.get<TelemetryMessageHandler>()
                    val subscription = Subscription(settings, handler)
                    // ready の条件(上の 1〜4)
                    val readiness =
                        listOf({ schemaIds.isReady }, { topics.isReady }, { subscription.isSubscribed }, { !handler.isRetrying })
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    scope.launch {
                        schemaIds.resolveUntilReady()
                        topics.awaitUntilReady()
                        subscription.run()
                    }
                    val server =
                        embeddedServer(CIO, configure = { connector { port = config.healthPort } }) {
                            routing {
                                get("/health/live") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
                                get("/health/ready") {
                                    if (readiness.all { it() }) {
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
                    logger.info("iot-bridge を起動しました(購読 {})", TelemetryMessageHandler.SUBSCRIPTION)
                    IotBridgeServer(server, koin, runtime, scope)
                }

        /** 設定とブリッジのユーザーのパスワードを読み、購読の設定を作る(serve だけが MQTT の設定を要る)。 */
        private fun serveInputs(env: Map<String, String>): Result<Pair<IotBridgeConfig, MqttSubscriberSettings>, ValidationError> =
            IotBridgeConfig.fromEnvironment(env).flatMap { config ->
                when (val password = EnvSecretProvider(env).get(IotBridgeConfig.MQTT_PASSWORD)) {
                    is Result.Ok -> config.subscriberSettings(password.value).map { config to it }
                    is Result.Err -> err(ValidationError.of(IotBridgeConfig.MQTT_PASSWORD.value, password.error.message))
                }
            }
    }
}

/** MQTT の購読。最初の接続か購読に失敗したら、新しいクライアントでやり直す([HiveMqTelemetrySubscriber.run] は 1 回だけ呼べる)。 */
private class Subscription(
    private val settings: MqttSubscriberSettings,
    private val handler: TelemetryMessageHandler,
) {
    @Volatile
    private var current: HiveMqTelemetrySubscriber? = null

    val isSubscribed: Boolean get() = current?.isSubscribed == true

    @Suppress("TooGenericExceptionCaught") // 接続・購読の失敗(ライブラリの例外の種類によらない)をやり直しに変える
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val subscriber = HiveMqTelemetrySubscriber(settings, handler).also { current = it }
            try {
                subscriber.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("MQTT の Broker に接続・購読できません({})。{} 後にやり直します", e::class.simpleName, IotBridgeServer.MQTT_RETRY_INTERVAL)
                delay(IotBridgeServer.MQTT_RETRY_INTERVAL)
            }
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(Subscription::class.java)
    }
}
