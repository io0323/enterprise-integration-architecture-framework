package io.eia.iotbridge.domain

import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val RECEIVED = Instant.parse("2026-10-25T00:00:00Z")
private val ADDRESS = DeviceAddress("t1", "d-001")

class TelemetryNormalizationSpec :
    FunSpec({
        context("トピック") {
            test("devices/{tenant}/{deviceId}/telemetry からデバイスを取る。キーは {tenant}/{deviceId}") {
                val address = DeviceAddress.fromTopic("devices/t1/d-001/telemetry").shouldBeInstanceOf<Result.Ok<DeviceAddress>>().value

                address shouldBe ADDRESS
                address.key shouldBe "t1/d-001"
            }

            test("形の違うトピックは INVALID_TOPIC") {
                listOf("devices/t1/d-001/status", "devices/t1/telemetry", "devices/T1/d-001/telemetry", "x/t1/d-001/telemetry").forEach {
                    DeviceAddress
                        .fromTopic(it)
                        .shouldBeInstanceOf<Result.Err<TelemetryRejection>>()
                        .error.reason shouldBe
                        RejectionReason.INVALID_TOPIC
                }
            }
        }

        context("形式の版をそろえる(ADR-0039 §4)") {
            test("版 1 の temp・hum は temperature(Cel)・humidity(%RH)にし、時刻は UNIX 秒から") {
                val telemetry =
                    TelemetryNormalization.normalize(
                        ADDRESS,
                        DevicePayload.V1("0123456789abcdef", 3, RECEIVED.epochSeconds - 60, 21.5, 40.0),
                        RECEIVED,
                    )

                telemetry shouldBe
                    Telemetry(
                        address = ADDRESS,
                        bootId = "0123456789abcdef",
                        seq = 3,
                        payloadVersion = 1,
                        deviceTime = RECEIVED - 60.seconds,
                        receivedAt = RECEIVED,
                        clockSuspect = false,
                        readings = listOf(Reading("temperature", 21.5, "Cel"), Reading("humidity", 40.0, "%RH")),
                        batteryPercent = null,
                    )
            }

            test("版 1 で測っていない値は測定値に入れない") {
                TelemetryNormalization
                    .normalize(ADDRESS, DevicePayload.V1("0123456789abcdef", 0, null, null, 55.0), RECEIVED)
                    .readings shouldBe listOf(Reading("humidity", 55.0, "%RH"))
            }

            test("版 2 はそのまま。電池の残りも運ぶ") {
                val readings = listOf(Reading("temperature", 20.0, "Cel"), Reading("co2", 410.0, null))
                val telemetry =
                    TelemetryNormalization.normalize(ADDRESS, DevicePayload.V2("0123456789abcdef", 9, RECEIVED, readings, 80), RECEIVED)

                telemetry.payloadVersion shouldBe 2
                telemetry.readings shouldBe readings
                telemetry.batteryPercent shouldBe 80
                telemetry.deviceTime shouldBe RECEIVED
            }

            test("メッセージの名前は {tenant}/{deviceId}/{boot}/{seq}(ce_id の元。番号が同じでも boot が違えば別の名前)") {
                val first = TelemetryNormalization.normalize(ADDRESS, DevicePayload.V1("aaaaaaaaaaaaaaaa", 0, null, null, null), RECEIVED)
                val afterReboot =
                    TelemetryNormalization.normalize(
                        ADDRESS,
                        DevicePayload.V1("bbbbbbbbbbbbbbbb", 0, null, null, null),
                        RECEIVED,
                    )

                first.identity shouldBe "t1/d-001/aaaaaaaaaaaaaaaa/0"
                (first.identity == afterReboot.identity) shouldBe false
            }
        }

        context("時計の疑わしさ(ADR-0039 §7)") {
            test("時刻がない・5 分を超えて未来・2020 年より前は疑う。過去にずれているだけ(遅れて届いた)は疑わない") {
                ClockCheck.suspect(null, RECEIVED) shouldBe true
                ClockCheck.suspect(RECEIVED + 5.minutes, RECEIVED) shouldBe false
                ClockCheck.suspect(RECEIVED + 5.minutes + 1.seconds, RECEIVED) shouldBe true
                ClockCheck.suspect(Instant.parse("1970-01-01T00:00:10Z"), RECEIVED) shouldBe true
                ClockCheck.suspect(RECEIVED - (60 * 24 * 30).minutes, RECEIVED) shouldBe false
            }

            test("疑わしくても値は直さない") {
                val future = RECEIVED + 1.minutes * 60
                val telemetry =
                    TelemetryNormalization.normalize(
                        ADDRESS,
                        DevicePayload.V2("0123456789abcdef", 0, future, emptyList(), null),
                        RECEIVED,
                    )

                telemetry.clockSuspect shouldBe true
                telemetry.deviceTime shouldBe future
            }
        }
    })
