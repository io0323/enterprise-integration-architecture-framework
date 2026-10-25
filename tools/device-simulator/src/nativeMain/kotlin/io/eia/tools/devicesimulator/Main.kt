package io.eia.tools.devicesimulator

import io.eia.shared.kernel.Result
import io.eia.shared.sdk.mqtt.MqttConfig
import io.eia.shared.sdk.mqtt.MqttPassword
import io.eia.shared.sdk.mqtt.MqttPublisher
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.system.exitProcess

private const val EXIT_FAILURES = 1
private const val EXIT_USAGE = 2

/**
 * `device-simulator`(P11 ④)。終了コード: 0 = すべて送れた、1 = 送れなかったものがある、2 = 引数・環境変数の誤り。
 * パスワードは `--password-env` の環境変数から読む(既定 MQTT_DEVICE_PASSWORD)。
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    if ("--help" in args) {
        println(SimulatorOptions.USAGE)
        return
    }
    val options =
        when (val parsed = SimulatorOptions.parse(args.toList())) {
            is Result.Ok -> parsed.value
            is Result.Err -> usage(parsed.error.message)
        }
    val password = getenv(options.passwordEnv)?.toKString()?.takeIf { it.isNotEmpty() } ?: usage("環境変数 ${options.passwordEnv} がありません")
    val publisher =
        MqttPublisher(
            MqttConfig(
                host = options.host,
                port = options.port,
                clientId = options.clientId,
                username = options.username,
                password = MqttPassword(password),
            ),
        )
    val failures =
        runBlocking {
            try {
                Simulator(options, publisher::publish, ::println).run()
            } finally {
                publisher.close()
            }
        }
    if (failures > 0) exitProcess(EXIT_FAILURES)
}

@OptIn(ExperimentalForeignApi::class)
private fun usage(reason: String): Nothing {
    platform.posix.fprintf(platform.posix.stderr, "%s\n\n%s\n", reason, SimulatorOptions.USAGE)
    exitProcess(EXIT_USAGE)
}
