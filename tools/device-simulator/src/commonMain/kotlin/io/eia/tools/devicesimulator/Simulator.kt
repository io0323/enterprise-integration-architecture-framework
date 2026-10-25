package io.eia.tools.devicesimulator

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.sdk.mqtt.MqttMessage
import io.eia.shared.sdk.mqtt.PublishResult
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * デバイスのふるまいを模擬して送る(ADR-0039)。
 *
 * 1. 通信が切れていた間に溜めたもの([SimulatorOptions.offline] 件)を、古い順にまとめて送る。デバイスの時刻は、溜め始めた時刻から
 *    間隔ごとにずらした過去の時刻(遅れて・まとめて届くデータ。§8)。
 * 2. その後、[SimulatorOptions.count] 件を間隔ごとに送る。
 *
 * デバイスの時刻には時計のずれ([SimulatorOptions.clockSkew])を足す。時計を持たないデバイスは時刻を送らない(§7)。
 * メッセージごとに traceparent をユーザープロパティに付け(§10)、送った結果を 1 行の JSON で [output] に出す
 * (E2E が traceparent でトレースをたどるため)。ペイロード・パスワードは出さない。
 *
 * @param publish 送り先(実際は SDK の `MqttPublisher.publish`)
 * @return 送れなかったメッセージの数
 */
@Suppress("LongParameterList") // 依存(既定値つき。テストで時計・待ち・乱数を差し替える)
class Simulator(
    private val options: SimulatorOptions,
    private val publish: suspend (MqttMessage) -> Result<PublishResult, DomainError>,
    private val output: (String) -> Unit,
    private val clock: Clock = Clock.System,
    private val sleep: suspend (Duration) -> Unit = { kotlinx.coroutines.delay(it) },
    private val session: BootSession = BootSession(),
    private val random: Random = Random.Default,
) {
    suspend fun run(): Int {
        var failures = 0
        val start = clock.now()
        for (index in 0 until options.offline) {
            if (!send(start - options.interval * (options.offline - index))) failures++
        }
        for (index in 0 until options.count) {
            if (index > 0) sleep(options.interval)
            if (!send(clock.now())) failures++
        }
        return failures
    }

    private suspend fun send(measuredAt: Instant): Boolean {
        val seq = session.next()
        val deviceTime = if (options.withClock) measuredAt + options.clockSkew else null
        val message =
            MqttMessage(
                topic = options.topic,
                payload = Telemetry.payload(options.payloadVersion, session, seq, deviceTime).encodeToByteArray(),
                contentType = CONTENT_TYPE,
            ).withTrace(options.traceParent, random = random)
        val traceParent = message.userProperties.first { it.name == MqttMessage.TRACEPARENT }.value
        val result = publish(message)
        output(line(seq, traceParent, deviceTime, result))
        return result is Result.Ok
    }

    private fun line(
        seq: Long,
        traceParent: String,
        deviceTime: Instant?,
        result: Result<PublishResult, DomainError>,
    ): String =
        buildString {
            append("""{"seq":$seq,"boot":"${session.boot}","traceparent":"$traceParent",""")
            append(""""deviceTime":${deviceTime?.let { "\"$it\"" } ?: "null"},""")
            when (result) {
                is Result.Ok -> append(""""result":"ok","reasonCode":${result.value.reasonCode}}""")
                is Result.Err -> append(""""result":"error","code":"${result.error.code}","message":"${escape(result.error.message)}"}""")
            }
        }

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val CONTENT_TYPE = "application/json"
    }
}
