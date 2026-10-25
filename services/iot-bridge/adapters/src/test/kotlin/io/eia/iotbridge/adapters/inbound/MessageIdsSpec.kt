package io.eia.iotbridge.adapters.inbound

import io.eia.iotbridge.domain.DeviceAddress
import io.eia.iotbridge.domain.Telemetry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID
import kotlin.time.Instant

private fun telemetry(
    boot: String,
    seq: Long,
) = Telemetry(
    address = DeviceAddress("t1", "d-001"),
    bootId = boot,
    seq = seq,
    payloadVersion = 2,
    deviceTime = null,
    receivedAt = Instant.parse("2026-10-25T00:00:00Z"),
    clockSuspect = true,
    readings = emptyList(),
    batteryPercent = null,
)

class MessageIdsSpec :
    FunSpec({
        test("RFC 9562 の UUID の版 5(DNS の名前空間と python.org の既知の値)") {
            MessageIds.nameBased(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "python.org") shouldBe
                UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d")
        }

        test("同じメッセージ(QoS 1 の再送・送り直し)は同じ ce_id。受け取った時刻などが違っても変わらない") {
            val first = telemetry("0123456789abcdef", 5)
            val resent = first.copy(receivedAt = Instant.parse("2026-10-25T01:00:00Z"))

            MessageIds.of(resent) shouldBe MessageIds.of(first)
            MessageIds.of(first).version() shouldBe 5
        }

        test("再起動で番号が振り直されても、boot が違うので ce_id は重ならない(正当なデータを重複として捨てない。ADR-0039 §5)") {
            MessageIds.of(telemetry("aaaaaaaaaaaaaaaa", 0)) shouldNotBe MessageIds.of(telemetry("bbbbbbbbbbbbbbbb", 0))
        }
    })
