package io.eia.tools.devicesimulator

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.eia.shared.resilience.trace.TraceParent
import io.eia.shared.sdk.mqtt.MqttMessage
import io.eia.shared.sdk.mqtt.MqttPublishRejected
import io.eia.shared.sdk.mqtt.PublishResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val START = Instant.parse("2026-10-25T00:00:00Z")

/** 待つたびに進む時計。 */
private class SteppingClock : Clock {
    var now: Instant = START

    override fun now(): Instant = now
}

private class Run(
    options: SimulatorOptions,
    result: (Int) -> Result<PublishResult, DomainError> = { ok(PublishResult(0)) },
) {
    val sent = mutableListOf<MqttMessage>()
    val lines = mutableListOf<String>()
    private val clock = SteppingClock()
    val simulator =
        Simulator(
            options = options,
            publish = { message ->
                sent += message
                result(sent.size - 1)
            },
            output = { lines += it },
            clock = clock,
            sleep = { clock.now += it },
            session = BootSession("0123456789abcdef"),
            random = Random(42),
        )

    fun payloads(): List<String> = sent.map { it.payload.decodeToString() }
}

class SimulatorSpec :
    FunSpec({
        test("count 件を間隔ごとに、デバイスのトピックへ QoS 1・application/json で送る。seq は 0 から") {
            val run = Run(SimulatorOptions(count = 3, interval = 10.seconds))

            run.simulator.run() shouldBe 0

            run.sent.map { it.topic }.toSet() shouldBe setOf("devices/t1/d-001/telemetry")
            run.sent.map { it.contentType }.toSet() shouldBe setOf("application/json")
            run.payloads() shouldBe
                listOf(0, 1, 2).map { seq ->
                    val time = START + (seq * 10).seconds
                    """{"v":2,"boot":"0123456789abcdef","seq":$seq,"time":"$time",""" +
                        """"readings":[{"name":"temperature","value":20.$seq,"unit":"Cel"},""" +
                        """{"name":"humidity","value":40.$seq,"unit":"%RH"}],""" +
                        """"battery":${100 - seq}}"""
                }
        }

        test("形式の版 1(古いファームウェア): 温度と湿度の決まった 2 つ、時刻は UNIX 秒") {
            val run = Run(SimulatorOptions(count = 1, payloadVersion = 1))

            run.simulator.run()

            run.payloads().single() shouldBe
                """{"v":1,"boot":"0123456789abcdef","seq":0,"ts":${START.epochSeconds},"temp":20.0,"hum":40.0}"""
        }

        test("通信が切れていた間の分(offline)を先に古い順に送る。デバイスの時刻は過去にずらす") {
            val run = Run(SimulatorOptions(count = 1, offline = 3, interval = 60.seconds, payloadVersion = 1))

            run.simulator.run()

            run.payloads().map { Regex(""""ts":(\d+)""").find(it)!!.groupValues[1].toLong() - START.epochSeconds } shouldBe
                listOf(-180L, -120L, -60L, 0L)
            run.payloads().map { Regex(""""seq":(\d+)""").find(it)!!.groupValues[1].toInt() } shouldBe listOf(0, 1, 2, 3)
        }

        test("時計のずれを足す。時計を持たないデバイスは時刻を送らない") {
            val skewed = Run(SimulatorOptions(count = 1, clockSkew = (-90).seconds, payloadVersion = 1))
            skewed.simulator.run()
            skewed.payloads().single() shouldContain """"ts":${START.epochSeconds - 90}"""

            val noClock = Run(SimulatorOptions(count = 1, withClock = false))
            noClock.simulator.run()
            noClock.payloads().single().contains("\"time\"") shouldBe false
            noClock.lines.single() shouldContain """"deviceTime":null"""
        }

        test("メッセージごとに traceparent を付け、親があればその子にする。送った結果を 1 行の JSON で出す") {
            val parent = TraceParent.generate(Random(1))
            val run = Run(SimulatorOptions(count = 2, traceParent = parent))

            run.simulator.run()

            val sent = run.sent.map { message -> message.userProperties.single { it.name == "traceparent" }.value }
            sent.forEach { it.substring(3, 35) shouldBe parent.traceId.toString() }
            sent.toSet().size shouldBe 2
            run.lines.first() shouldBe
                """{"seq":0,"boot":"0123456789abcdef","traceparent":"${sent.first()}","deviceTime":"$START","result":"ok","reasonCode":0}"""
        }

        test("送れなかった数を返し、結果の行に理由を出す(ペイロードは出さない)") {
            val run = Run(SimulatorOptions(count = 3)) { index -> if (index == 1) err(MqttPublishRejected(0x87)) else ok(PublishResult(0)) }

            run.simulator.run() shouldBe 1

            run.lines[1] shouldContain """"result":"error","code":"mqtt_publish_rejected""""
            run.lines[1].contains("readings") shouldBe false
        }

        test("起動のたびに boot が変わる(番号が振り直されても ce_id が重ならないように。ADR-0039 §5)") {
            val boots = (1..20).map { BootSession().boot }

            boots.toSet().size shouldBe 20
            boots.forEach { Regex("^[0-9a-f]{32}$").matches(it) shouldBe true }
        }
    })

private operator fun Duration.times(value: Int): Duration = this * value.toDouble()
