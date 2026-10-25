package io.eia.iotbridge.adapters.inbound

import io.eia.iotbridge.domain.DevicePayload
import io.eia.iotbridge.domain.Reading
import io.eia.iotbridge.domain.RejectionReason
import io.eia.iotbridge.domain.TelemetryRejection
import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Instant

private const val BOOT = "0123456789abcdef"
private val decoder = DevicePayloadDecoder()

private fun decode(json: String): Result<DevicePayload, TelemetryRejection> = decoder.decode(json.encodeToByteArray())

private fun rejection(json: String): TelemetryRejection = decode(json).shouldBeInstanceOf<Result.Err<TelemetryRejection>>().error

class DevicePayloadDecoderSpec :
    FunSpec({
        test("リソースの形式のスキーマは契約(contracts/devices)と同じで、受け付ける版はそのすべて") {
            val contracts = Path.of(checkNotNull(System.getProperty("eia.contractsDevices")))
            val files = contracts.listDirectoryEntries("telemetry.v*.schema.json")
            files.map { Regex("""v(\d+)""").find(it.name)!!.groupValues[1].toInt() }.toSet() shouldBe
                DevicePayloadDecoder.SUPPORTED_VERSIONS
            files.forEach { file ->
                DevicePayloadDecoder::class.java.getResource("/contracts/devices/${file.name}")!!.readText() shouldBe file.readText()
            }
        }

        test("版 1: boot・seq・UNIX 秒の時刻・温度と湿度") {
            decode("""{"v":1,"boot":"$BOOT","seq":7,"ts":1792886077,"temp":20.5,"hum":40}""") shouldBe
                Result.Ok(DevicePayload.V1(BOOT, 7, 1792886077, 20.5, 40.0))
        }

        test("版 2: 測定値の一覧・ミリ秒の時刻・電池。未知の項目は無視する(Tolerant Reader)") {
            val json =
                """{"v":2,"boot":"$BOOT","seq":1,"time":"2026-10-25T00:00:00.123Z","firmware":"2.4.1",""" +
                    """"readings":[{"name":"temperature","value":20,"unit":"Cel","x":1},{"name":"co2","value":410.5}],"battery":80}"""

            decode(json) shouldBe
                Result.Ok(
                    DevicePayload.V2(
                        boot = BOOT,
                        seq = 1,
                        time = Instant.parse("2026-10-25T00:00:00.123Z"),
                        readings = listOf(Reading("temperature", 20.0, "Cel"), Reading("co2", 410.5, null)),
                        battery = 80,
                    ),
                )
        }

        test("時刻のないペイロード(時計のないデバイス)も受け付ける") {
            decode("""{"v":2,"boot":"$BOOT","seq":0,"readings":[{"name":"temperature","value":1}]}""")
                .shouldBeInstanceOf<Result.Ok<DevicePayload.V2>>()
                .value.time shouldBe null
        }

        test("知らない版は UNSUPPORTED_PAYLOAD_VERSION(捨てずに DLQ)") {
            rejection("""{"v":3,"boot":"$BOOT","seq":0}""") shouldBe TelemetryRejection(RejectionReason.UNSUPPORTED_PAYLOAD_VERSION, "v")
        }

        test("JSON でない・オブジェクトでない・v がない・大きすぎるものは INVALID_PAYLOAD") {
            mapOf(
                "{not json" to "payload:json",
                "[1,2]" to "payload:type",
                """{"boot":"$BOOT","seq":0}""" to "v:type",
                """{"v":"2"}""" to "v:type",
                """{"v":1,"boot":"$BOOT","seq":0,"pad":"${"x".repeat(DevicePayloadDecoder.MAX_PAYLOAD_BYTES)}"}""" to "payload:maxLength",
            ).forEach { (json, detail) -> rejection(json) shouldBe TelemetryRejection(RejectionReason.INVALID_PAYLOAD, detail) }
        }

        test("スキーマに合わないものは INVALID_PAYLOAD。説明は位置とキーワードだけで、値を含めない") {
            val secretLike = "ZZZZ-not-a-boot-ZZZZ"
            val error = rejection("""{"v":2,"boot":"$secretLike","seq":-1,"readings":[]}""")

            error.reason shouldBe RejectionReason.INVALID_PAYLOAD
            error.detail shouldBe "/boot:pattern,/readings:minItems,/seq:minimum"
            error.message shouldNotContain secretLike
        }

        test("必須の項目(boot・seq)のないものは INVALID_PAYLOAD(重複の判定ができないため)") {
            rejection("""{"v":1,"seq":0}""").detail shouldBe ":required"
        }
    })
