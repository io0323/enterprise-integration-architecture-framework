package io.eia.tools.devicesimulator

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.trace.TraceParent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * シミュレータの引数(`device-simulator --help`)。資格情報は引数に書かせず、環境変数の名前([passwordEnv])を受け取る
 * (シェルの履歴やプロセスの一覧に残さない)。
 *
 * @param offline 接続する前に、通信が切れていた間に溜めたものとして作るメッセージの数(デバイスの時刻は過去にずらす。ADR-0039 §8)
 * @param clockSkew デバイスの時計のずれ(ADR-0039 §7)
 * @param withClock false ならデバイスの時刻を送らない(時計が合っていないデバイス)
 * @param traceParent 呼び出し元のトレース。あれば各メッセージをその子にする。なければメッセージごとに新しいトレースを始める
 */
data class SimulatorOptions(
    val host: String = "localhost",
    val port: Int = DEFAULT_PORT,
    val tenant: String = "t1",
    val deviceId: String = "d-001",
    val passwordEnv: String = DEFAULT_PASSWORD_ENV,
    val count: Int = DEFAULT_COUNT,
    val interval: Duration = DEFAULT_INTERVAL,
    val payloadVersion: Int = LATEST_PAYLOAD_VERSION,
    val offline: Int = 0,
    val clockSkew: Duration = Duration.ZERO,
    val withClock: Boolean = true,
    val traceParent: TraceParent? = null,
) {
    val topic: String get() = "devices/$tenant/$deviceId/telemetry"
    val username: String get() = "$tenant/$deviceId"
    val clientId: String get() = "$tenant-$deviceId"

    companion object {
        const val DEFAULT_PORT = 19883
        const val DEFAULT_PASSWORD_ENV = "MQTT_DEVICE_PASSWORD"
        const val DEFAULT_COUNT = 10
        val DEFAULT_INTERVAL: Duration = 1.seconds
        const val LATEST_PAYLOAD_VERSION = 2
        val PAYLOAD_VERSIONS = 1..LATEST_PAYLOAD_VERSION

        // トピックの {tenant}・{deviceId}(小文字の英数字と -。contracts/asyncapi/device-telemetry.v1.yaml)
        private val SEGMENT = Regex("^[a-z0-9][a-z0-9-]*$")
        private val ENV_NAME = Regex("^[A-Z_][A-Z0-9_]*$")
        private const val MAX_PORT = 65535

        val USAGE: String =
            """
            使い方: device-simulator [オプション]
              --host <host>                 MQTT Broker(既定 localhost)
              --port <port>                 ポート(既定 $DEFAULT_PORT)
              --tenant <tenant>             テナント(既定 t1)
              --device <deviceId>           デバイスの ID(既定 d-001)。ユーザー名は {tenant}/{deviceId}
              --password-env <NAME>         パスワードを読む環境変数の名前(既定 $DEFAULT_PASSWORD_ENV)
              --count <n>                   送るメッセージの数(既定 $DEFAULT_COUNT)
              --interval-ms <ms>            送る間隔(既定 ${DEFAULT_INTERVAL.inWholeMilliseconds})
              --payload-version <1|2>       ペイロードの形式の版(既定 $LATEST_PAYLOAD_VERSION。1 は古いファームウェア)
              --offline <n>                 接続の前に溜めていたものとして送る数(デバイスの時刻は過去)
              --clock-skew-seconds <s>      デバイスの時計のずれ(負の値は遅れ)
              --no-clock                    デバイスの時刻を送らない
              --traceparent <value>         親のトレース(W3C traceparent)
            送ったメッセージごとに、標準出力に 1 行の JSON(seq・boot・traceparent・結果)を出す。
            """.trimIndent()

        /** [args] を読む。誤りは ValidationError(使い方を出して終わる)。 */
        @Suppress("ReturnCount") // 誤りは見つけたところで返す
        fun parse(args: List<String>): Result<SimulatorOptions, ValidationError> {
            var options = SimulatorOptions()
            var index = 0
            while (index < args.size) {
                val name = args[index]
                if (name == "--no-clock") {
                    options = options.copy(withClock = false)
                    index++
                } else {
                    val value = args.getOrNull(index + 1) ?: return invalid(name, "値がありません")
                    options =
                        when (val applied = options.with(name, value)) {
                            is Result.Ok -> applied.value
                            is Result.Err -> return applied
                        }
                    index += 2
                }
            }
            return ok(options)
        }

        /** 値をとる 1 つのオプションを反映する。 */
        @Suppress("CyclomaticComplexMethod", "ReturnCount") // 引数の名前ごとの分岐
        private fun SimulatorOptions.with(
            name: String,
            value: String,
        ): Result<SimulatorOptions, ValidationError> {
            val number = value.toLongOrNull()
            val updated =
                when (name) {
                    "--host" -> {
                        copy(host = value)
                    }

                    "--port" -> {
                        copy(port = number?.takeIf { it in 1..MAX_PORT }?.toInt() ?: return invalid(name, "1〜$MAX_PORT"))
                    }

                    "--tenant" -> {
                        copy(tenant = value.takeIf(SEGMENT::matches) ?: return invalid(name, "小文字の英数字と -"))
                    }

                    "--device" -> {
                        copy(deviceId = value.takeIf(SEGMENT::matches) ?: return invalid(name, "小文字の英数字と -"))
                    }

                    "--password-env" -> {
                        copy(passwordEnv = value.takeIf(ENV_NAME::matches) ?: return invalid(name, "環境変数の名前"))
                    }

                    "--count" -> {
                        copy(count = number?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: return invalid(name, "0 以上の整数"))
                    }

                    "--interval-ms" -> {
                        copy(interval = number?.takeIf { it >= 0 }?.milliseconds ?: return invalid(name, "0 以上の整数"))
                    }

                    "--payload-version" -> {
                        copy(payloadVersion = number?.toInt()?.takeIf { it in PAYLOAD_VERSIONS } ?: return invalid(name, "1 か 2"))
                    }

                    "--offline" -> {
                        copy(offline = number?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: return invalid(name, "0 以上の整数"))
                    }

                    "--clock-skew-seconds" -> {
                        copy(clockSkew = number?.seconds ?: return invalid(name, "整数"))
                    }

                    "--traceparent" -> {
                        when (val parsed = TraceParent.parse(value)) {
                            is Result.Ok -> copy(traceParent = parsed.value)
                            is Result.Err -> return err(parsed.error)
                        }
                    }

                    else -> {
                        return invalid(name, "知らないオプションです")
                    }
                }
            return ok(updated)
        }

        private fun invalid(
            name: String,
            reason: String,
        ): Result<Nothing, ValidationError> = err(ValidationError.of(name, reason))
    }
}
