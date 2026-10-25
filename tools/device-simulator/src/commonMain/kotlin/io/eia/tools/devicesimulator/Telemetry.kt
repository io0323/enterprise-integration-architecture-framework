package io.eia.tools.devicesimulator

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * 起動のたびに作るセッション(ADR-0039 §5)。`boot` は乱数で作るセッション ID(Uuid の 128 ビットの 16 進。暗号論的な乱数)、
 * `seq` は起動ごとに 0 から数える番号。番号が振り直されても、`boot` が違うので以前のメッセージと ce_id が重ならない。
 */
class BootSession(
    val boot: String = newBoot(),
) {
    private var nextSeq = 0L

    init {
        require(BOOT.matches(boot)) { "boot は 16〜32 桁の小文字の 16 進です" }
    }

    fun next(): Long = nextSeq++

    companion object {
        private val BOOT = Regex("^[0-9a-f]{16,32}$")

        fun newBoot(): String = Uuid.random().toHexString()
    }
}

/**
 * テレメトリのペイロード(contracts/devices/telemetry.v{n}.schema.json)。測定値は [seq] から決まる値にする(テストで確かめやすいように)。
 *
 * @param deviceTime デバイスの時計での時刻(ずれを含む)。null なら送らない(時計が合っていないデバイス)
 */
object Telemetry {
    private const val BASE_TEMPERATURE_TENTHS = 200
    private const val TEMPERATURE_STEPS = 50
    private const val BASE_HUMIDITY_TENTHS = 400
    private const val HUMIDITY_STEPS = 200
    private const val FULL_BATTERY = 100
    private const val BATTERY_STEPS = 50
    private const val TENTHS = 10

    fun payload(
        version: Int,
        session: BootSession,
        seq: Long,
        deviceTime: Instant?,
    ): String {
        val temperature = tenths(BASE_TEMPERATURE_TENTHS + (seq % TEMPERATURE_STEPS).toInt())
        val humidity = tenths(BASE_HUMIDITY_TENTHS + (seq % HUMIDITY_STEPS).toInt())
        return when (version) {
            1 -> {
                buildString {
                    append("""{"v":1,"boot":"${session.boot}","seq":$seq""")
                    deviceTime?.let { append(""","ts":${it.epochSeconds}""") }
                    append(""","temp":$temperature,"hum":$humidity}""")
                }
            }

            2 -> {
                buildString {
                    append("""{"v":2,"boot":"${session.boot}","seq":$seq""")
                    // ミリ秒の精度の RFC 3339(UTC。末尾は Z)
                    deviceTime?.let { append(""","time":"${Instant.fromEpochMilliseconds(it.toEpochMilliseconds())}"""") }
                    append(""","readings":[""")
                    append("""{"name":"temperature","value":$temperature,"unit":"Cel"},""")
                    append("""{"name":"humidity","value":$humidity,"unit":"%RH"}""")
                    append("""],"battery":${FULL_BATTERY - (seq % BATTERY_STEPS)}}""")
                }
            }

            else -> {
                throw IllegalArgumentException("知らない形式の版です: $version")
            }
        }
    }

    /** 10 分の 1 の単位の整数を、小数点 1 桁の JSON の数値にする(ロケールに依存しない)。 */
    private fun tenths(value: Int): String = "${value / TENTHS}.${value % TENTHS}"
}
