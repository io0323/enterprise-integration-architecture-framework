package io.eia.iotbridge.app

import io.eia.iotbridge.adapters.inbound.DevicePayloadDecoder
import io.eia.iotbridge.adapters.inbound.TelemetryDeadLetterRedelivery
import io.eia.iotbridge.adapters.outbound.IotEventSchemas
import io.eia.platform.messagingkafka.DeadLetterPublisher
import io.eia.platform.messagingkafka.DeadLetterReplayer
import io.eia.platform.messagingkafka.ReplayCommandLine
import io.eia.platform.messagingkafka.ReplayRequest
import io.eia.platform.observability.Observability
import io.eia.platform.observability.ObservabilityConfig
import io.eia.platform.schemaregistry.SchemaIdBook
import io.eia.shared.kernel.Result
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.producer.Producer
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import java.io.PrintStream

/**
 * `iot-bridge reprocess-dlq --limit <n> [--execute] [条件]`: `iot.telemetry.reported.v1.dlq` のメッセージを、**デコードからやり直して**
 * `iot.telemetry.reported.v1` に発行する(ADR-0039 §4 の再処理。デコーダを直した・足した後に使う)。
 *
 * - DLQ の読み取り・条件・件数の上限・既定の dry-run・表示・終了コードは `make dlq-replay` と同じ部品([DeadLetterReplayer]・
 *   [ReplayCommandLine])。違うのは 1 件の処理し直し方([TelemetryDeadLetterRedelivery])だけ。DLQ は決まっているので `--topic` は取らない。
 * - dry-run は、今のデコーダで読めるかを 1 件ずつ確かめる(PLANNED か REJECTED)。送らないので、スキーマ ID の解決も要らない。
 * - --execute は、スキーマ ID を解決してから発行する。まだ受け付けないものは REJECTED で、DLQ に入れ直さない(DLQ のレコードは残る)。
 *   ce_id は受け取ったときと同じ値になる(ADR-0039 §5)ので、同じものを 2 回再処理しても、消費者は 1 回だけ処理する。
 * - 使う設定は `IOT_BRIDGE_KAFKA_BOOTSTRAP`・`IOT_BRIDGE_SCHEMA_REGISTRY_URL` だけ(MQTT には接続しない)。
 *
 * 終了コード: 0 = 終えた / 1 = 処理できなかったもの(REJECTED・FAILED など)がある / 2 = 実行できない(引数・設定の誤り、Kafka・レジストリに
 * 接続できない)。出力に値は出さない。
 */
internal class ReprocessDlqCommand(
    private val decoder: DevicePayloadDecoder = DevicePayloadDecoder(),
    private val connect: (bootstrapServers: String) -> DeadLetterReplayer = { DeadLetterReplayer.connect(it, CLIENT_ID) },
) {
    /** 実行して終了コードを返す。想定外の例外も [ReplayCommandLine.FAILED] にする(例外のメッセージは値を含みうるので、クラス名だけを出す)。 */
    @Suppress("TooGenericExceptionCaught") // 境界で全例外を終了コード 2 に変換するのがこの関数の責務
    fun run(
        args: List<String>,
        env: Map<String, String>,
        out: PrintStream,
    ): Int =
        try {
            reprocess(args, env, out)
        } catch (e: Exception) {
            out.println("実行できません: 想定外のエラー(${e::class.simpleName})")
            ReplayCommandLine.FAILED
        }

    @Suppress("ReturnCount") // 引数・設定の誤りのそれぞれで、接続する前に返す
    private fun reprocess(
        args: List<String>,
        env: Map<String, String>,
        out: PrintStream,
    ): Int {
        val request =
            when (val parsed = ReplayCommandLine.parse(args, deadLetterTopic = DEAD_LETTER_TOPIC)) {
                is Result.Ok -> {
                    parsed.value.request
                }

                is Result.Err -> {
                    out.println("引数: ${parsed.error}")
                    out.println(USAGE)
                    return ReplayCommandLine.FAILED
                }
            }
        val config =
            when (val loaded = IotBridgeConfig.fromEnvironment(env)) {
                is Result.Ok -> {
                    loaded.value
                }

                is Result.Err -> {
                    loaded.error.violations.forEach { out.println("設定: ${it.field}: ${it.reason}") }
                    return ReplayCommandLine.FAILED
                }
            }
        val observability =
            when (val loaded = ObservabilityConfig.fromEnvironment(mapOf(ObservabilityConfig.ENV_SERVICE_NAME to SERVICE_NAME) + env)) {
                is Result.Ok -> {
                    loaded.value
                }

                is Result.Err -> {
                    loaded.error.violations.forEach { out.println("設定: ${it.field}: ${it.reason}") }
                    return ReplayCommandLine.FAILED
                }
            }
        return Observability.init(observability).use { runtime ->
            val koin = koinApplication { modules(iotBridgeModule(config, runtime, CLIENT_ID, decoder)) }.koin
            try {
                runBlocking { execute(koin, request, config, out) }
            } finally {
                koin.get<Producer<ByteArray, ByteArray>>().close()
                koin.get<HttpClient>().close()
                koin.close()
            }
        }
    }

    private suspend fun execute(
        koin: Koin,
        request: ReplayRequest,
        config: IotBridgeConfig,
        out: PrintStream,
    ): Int {
        if (request.execute) {
            val resolved = koin.get<SchemaIdBook>().resolve()
            if (resolved is Result.Err) {
                out.println("実行できません: 書くイベントのスキーマ ID を解決できません(${resolved.error.message})")
                return ReplayCommandLine.FAILED
            }
        }
        return connect(config.kafkaBootstrap).use { replayer ->
            when (val result = replayer.replay(request, koin.get<TelemetryDeadLetterRedelivery>())) {
                is Result.Ok -> {
                    ReplayCommandLine.print(result.value, out)
                    ReplayCommandLine.exitCode(result.value)
                }

                is Result.Err -> {
                    out.println("実行できません: ${result.error.message}")
                    ReplayCommandLine.FAILED
                }
            }
        }
    }

    companion object {
        val DEAD_LETTER_TOPIC: String = DeadLetterPublisher.deadLetterTopic(IotEventSchemas.TELEMETRY_REPORTED.name)
        private const val SERVICE_NAME = "iot-bridge"
        private const val CLIENT_ID = "iot-bridge-reprocess"

        val USAGE: String =
            """
            |使い方: iot-bridge reprocess-dlq --limit <1-${ReplayRequest.MAX_LIMIT}> [--execute] [条件]
            |  $DEAD_LETTER_TOPIC をデコードからやり直して発行する(ADR-0039 §4)。既定は dry-run(今のデコーダで読めるかの一覧だけ)。
            |${ReplayCommandLine.CONDITIONS.prependIndent("  ")}
            """.trimMargin()
    }
}
