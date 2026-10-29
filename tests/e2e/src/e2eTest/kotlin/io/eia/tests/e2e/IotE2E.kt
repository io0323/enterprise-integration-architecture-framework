@file:Suppress("MagicNumber") // 件数・待ち時間・MQTT の理由のコード

package io.eia.tests.e2e

import io.eia.shared.kernel.Result
import io.eia.shared.sdk.mqtt.MqttConfig
import io.eia.shared.sdk.mqtt.MqttMessage
import io.eia.shared.sdk.mqtt.MqttPassword
import io.eia.shared.sdk.mqtt.MqttPublishRejected
import io.eia.shared.sdk.mqtt.MqttPublisher
import io.eia.shared.sdk.mqtt.PublishResult
import io.eia.tests.e2e.E2eEnvironment.json
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.io.path.readLines

private const val TOPIC = "iot.telemetry.reported.v1"
private const val TENANT = "t1"
private val UUID_V5 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

/** native のシミュレータ(tests/e2e/build.gradle.kts が、この OS の release の実行ファイルを組み立てて渡す)。 */
private val simulator: String =
    System.getProperty("eiaf.deviceSimulator")
        ?: error("この OS のシミュレータの実行ファイルがありません(linuxX64 と macosArm64 だけ。make e2e で実行してください)")

/**
 * デバイスのパスワード(`make env` が作る infra/local/secrets/mqtt/device-passwords。1 行に「{tenant}/{deviceId} パスワード」)。
 * 値は出力しない。シミュレータには環境変数で渡す(引数・プロセスの一覧に残さない)。
 */
private fun password(device: String): String =
    Path
        .of(System.getProperty("eiaf.repo.root", "."))
        .resolve("infra/local/secrets/mqtt/device-passwords")
        .readLines()
        .map { it.trim().split(Regex("\\s+")) }
        .firstOrNull { it.size == 2 && it[0] == "$TENANT/$device" }
        ?.get(1) ?: error("$TENANT/$device のパスワードがありません(make env を実行してください)")

/** シミュレータで [device] として送り、標準出力の行(1 メッセージ 1 行の JSON。seq・boot・traceparent・結果)を返す。 */
private fun simulate(
    device: String,
    vararg options: String,
): List<JsonObject> {
    val process =
        ProcessBuilder(simulator, "--tenant", TENANT, "--device", device, *options)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply { environment()["MQTT_DEVICE_PASSWORD"] = password(device) }
            .start()
    val lines = process.inputStream.bufferedReader().readLines()
    process.waitFor(2, TimeUnit.MINUTES) shouldBe true
    process.exitValue() shouldBe 0
    return lines.filter { it.startsWith("{") }.map { json(it).jsonObject }
}

private fun JsonObject.traceParent(): String = text("traceparent")

/** traceparent の trace-id と parent-id(16 進)。 */
private fun String.traceId(): String = split("-")[1]

private fun String.parentId(): String = split("-")[2]

/** [device] の [boot] のテレメトリを、[count] 件そろうまで読む(seq の順。キーが同じなので同じパーティションに順に並ぶ)。 */
private fun telemetry(
    device: String,
    boot: String,
    count: Int,
): List<Pair<ConsumerRecord<ByteArray?, ByteArray?>, GenericRecord>> {
    val found = mutableListOf<Pair<ConsumerRecord<ByteArray?, ByteArray?>, GenericRecord>>()
    SagaKafka.records(TOPIC, enough = count, timeout = Duration.ofMinutes(2)) { record ->
        val payload =
            record
                .takeIf { it.key()?.toString(Charsets.UTF_8) == "$TENANT/$device" }
                ?.value()
                ?.let(SagaKafka::decode)
                ?.takeIf { it.get("bootId").toString() == boot }
        payload?.let { found += record to it }
        payload != null
    }
    found shouldHaveSize count
    return found.sortedBy { it.first.offset() }
}

private fun ConsumerRecord<ByteArray?, ByteArray?>.header(name: String): String? = SagaKafka.header(this, name)

private fun randomBoot(): String = HexFormat.of().formatHex(ByteArray(8).also(SecureRandom()::nextBytes))

/** デバイス([device])の資格情報で、SDK の MQTT の部分から送る(シミュレータではできない操作: 他のトピック・同じメッセージの再送)。 */
private fun <T> asDevice(
    device: String,
    block: suspend (MqttPublisher) -> T,
): T =
    runBlocking {
        val publisher =
            MqttPublisher(
                MqttConfig(
                    host = "localhost",
                    port = 19883,
                    clientId = "e2e-$TENANT-$device",
                    username = "$TENANT/$device",
                    password = MqttPassword(password(device)),
                ),
            )
        try {
            block(publisher)
        } finally {
            publisher.close()
        }
    }

/**
 * ROADMAP P11 の DoD: native のシミュレータから送ったテレメトリが、Mosquitto → iot-bridge → `iot.telemetry.reported.v1` に届き、
 * MQTT v5 のユーザープロパティで運んだ traceparent で Tempo までたどれる(ADR-0039)。デバイスの資格情報と ACL・形式の版・再送・
 * 通信が切れていた間の分も、ローカル基盤(`make up PROFILE="... iot" E2E=1`)の公開されたポートだけで確かめる。
 */
class IotE2E :
    FunSpec({
        test("native のシミュレータのテレメトリが Kafka に届き、MQTT v5 の traceparent で iot-bridge の span まで Tempo でたどれる") {
            val sent = simulate("d-001", "--count", "3", "--interval-ms", "200")
            sent shouldHaveSize 3
            sent.forEach { it.text("result") shouldBe "ok" }
            val boot = sent.first().text("boot")

            val received = telemetry("d-001", boot, 3)
            received.map { (_, payload) -> payload.get("seq") as Long } shouldContainExactly listOf(0L, 1L, 2L)
            received.forEach { (record, payload) ->
                record.header("ce_type") shouldBe "iot.telemetry.reported"
                record.header("ce_source") shouldBe "/iot/iot-bridge"
                record.header("ce_id")!! shouldMatch UUID_V5
                payload.get("payloadVersion") shouldBe 2
                payload.get("clockSuspect") shouldBe false
                // Kafka のヘッダの traceparent は、デバイスが MQTT のユーザープロパティで送ったトレースの中にある
                val deviceTrace = sent.single { it.text("seq").toLong() == payload.get("seq") }.traceParent()
                record.header("traceparent")!!.traceId() shouldBe deviceTrace.traceId()
            }

            // Tempo: デバイスの span の子に iot-bridge の受信(CONSUMER)、その子に Kafka への発行(PRODUCER)
            val deviceTrace = sent.first().traceParent()
            val spans =
                eventually {
                    spans(deviceTrace.traceId()).takeIf { all -> all.any { it.service == "iot-bridge" && it.kind == "SPAN_KIND_PRODUCER" } }
                }.shouldNotBeNull()
            val consumer = spans.single { it.service == "iot-bridge" && it.kind == "SPAN_KIND_CONSUMER" }
            consumer.parentSpanId shouldBe deviceTrace.parentId()
            spans.single { it.service == "iot-bridge" && it.kind == "SPAN_KIND_PRODUCER" }.parentSpanId shouldBe consumer.spanId
        }

        test("古いファームウェア(形式の版 1)のテレメトリも、同じ形(測定値の一覧)にそろえて届く") {
            val sent = simulate("d-002", "--count", "1", "--payload-version", "1")
            sent.single().text("result") shouldBe "ok"

            val (_, payload) = telemetry("d-002", sent.single().text("boot"), 1).single()
            payload.get("payloadVersion") shouldBe 1
            @Suppress("UNCHECKED_CAST")
            val readings = payload.get("readings") as List<GenericRecord>
            readings.associate { it.get("name").toString() to it.get("unit")?.toString() } shouldBe
                mapOf("temperature" to "Cel", "humidity" to "%RH")
        }

        test("通信が切れていた間の分(--offline)は、デバイスの時刻が過去のまま古い順に届き、遅れたことを疑わない") {
            // 溜めていた 3 件のデバイスの時刻は 3 分前・2 分前・1 分前(間隔 60 秒)。続けて今の 1 件を送る
            val sent = simulate("d-003", "--offline", "3", "--count", "1", "--interval-ms", "60000")
            sent shouldHaveSize 4
            sent.forEach { it.text("result") shouldBe "ok" }

            val received = telemetry("d-003", sent.first().text("boot"), 4)
            received.map { (_, payload) -> payload.get("seq") as Long } shouldContainExactly listOf(0L, 1L, 2L, 3L)
            received.take(3).forEach { (_, payload) ->
                val lagMicros = (payload.get("receivedAt") as Long) - (payload.get("deviceTime") as Long)
                lagMicros shouldBeGreaterThan 50_000_000L
                payload.get("clockSuspect") shouldBe false
            }
        }

        test("同じメッセージの再送(QoS 1)は同じ ce_id で届く(取り除くのは消費者の inbox。ADR-0039 §6)") {
            val boot = randomBoot()
            val payload = """{"v":2,"boot":"$boot","seq":0,"readings":[{"name":"temperature","value":21.5,"unit":"Cel"}]}"""
            asDevice("d-001") { publisher ->
                repeat(2) {
                    publisher
                        .publish(MqttMessage("devices/$TENANT/d-001/telemetry", payload.toByteArray()))
                        .shouldBeInstanceOf<Result.Ok<PublishResult>>()
                }
            }

            val received = telemetry("d-001", boot, 2)
            received.map { (record, _) -> record.header("ce_id") }.toSet() shouldHaveSize 1
        }

        test("デバイスは他のデバイスのトピックに書けない(PUBACK 0x87)。iot-bridge には届かない") {
            val boot = randomBoot()
            val payload = """{"v":2,"boot":"$boot","seq":0,"readings":[{"name":"temperature","value":21.5,"unit":"Cel"}]}"""
            asDevice("d-001") { publisher ->
                publisher
                    .publish(MqttMessage("devices/$TENANT/d-002/telemetry", payload.toByteArray()))
                    .shouldBeInstanceOf<Result.Err<*>>()
                    .error shouldBe MqttPublishRejected(0x87)
            }

            SagaKafka.records(TOPIC, timeout = Duration.ofSeconds(15)) { record ->
                record
                    .value()
                    ?.let(SagaKafka::decode)
                    ?.get("bootId")
                    ?.toString() == boot
            } shouldHaveSize 0
        }
    })
