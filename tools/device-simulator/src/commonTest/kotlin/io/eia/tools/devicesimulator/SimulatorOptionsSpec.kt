package io.eia.tools.devicesimulator

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.resilience.trace.TraceParent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SimulatorOptionsSpec :
    FunSpec({
        test("既定値: localhost:19883・t1/d-001・10 件・1 秒ごと・形式の版 2。トピックとユーザー名はテナントとデバイスの ID から作る") {
            val options = SimulatorOptions.parse(emptyList()).shouldBeInstanceOf<Result.Ok<SimulatorOptions>>().value

            options shouldBe SimulatorOptions()
            options.topic shouldBe "devices/t1/d-001/telemetry"
            options.username shouldBe "t1/d-001"
            options.clientId shouldBe "t1-d-001"
        }

        test("すべてのオプションを読む") {
            val parent = TraceParent.generate(Random(1))
            val args =
                listOf(
                    "--host",
                    "mosquitto",
                    "--port",
                    "1883",
                    "--tenant",
                    "acme",
                    "--device",
                    "d-9",
                    "--password-env",
                    "PW",
                    "--count",
                    "3",
                    "--interval-ms",
                    "250",
                    "--payload-version",
                    "1",
                    "--offline",
                    "5",
                    "--clock-skew-seconds",
                    "-30",
                    "--no-clock",
                    "--traceparent",
                    parent.format(),
                )

            SimulatorOptions.parse(args).shouldBeInstanceOf<Result.Ok<SimulatorOptions>>().value shouldBe
                SimulatorOptions(
                    host = "mosquitto",
                    port = 1883,
                    tenant = "acme",
                    deviceId = "d-9",
                    passwordEnv = "PW",
                    count = 3,
                    interval = 250.milliseconds,
                    payloadVersion = 1,
                    offline = 5,
                    clockSkew = (-30).seconds,
                    withClock = false,
                    traceParent = parent,
                )
        }

        test("誤り: 値がない・範囲外・形式の誤り・知らないオプション") {
            listOf(
                listOf("--port"),
                listOf("--port", "0"),
                listOf("--payload-version", "3"),
                listOf("--device", "D/1"),
                listOf("--password-env", "lower"),
                listOf("--count", "-1"),
                listOf("--traceparent", "nope"),
                listOf("--password", "secret"),
            ).forEach { SimulatorOptions.parse(it).shouldBeInstanceOf<Result.Err<ValidationError>>() }
        }
    })
