package io.eia.iotbridge.app

import io.eia.platform.security.secret.Secret
import io.eia.shared.kernel.Result
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream

private val REQUIRED =
    mapOf(
        IotBridgeConfig.KAFKA_BOOTSTRAP to "kafka:9092",
        IotBridgeConfig.SCHEMA_REGISTRY_URL to "http://apicurio:8080/apis/registry/v3",
    )

private fun <T> Result<T, *>.ok(): T =
    when (this) {
        is Result.Ok -> value
        is Result.Err -> fail("Ok を期待しましたが Err でした: $error")
    }

private fun run(
    args: List<String>,
    env: Map<String, String> = REQUIRED,
): Pair<Int, String> {
    val buffer = ByteArrayOutputStream()
    val code = IotBridgeCommands.run(args, env, PrintStream(buffer, true, Charsets.UTF_8))
    return code to buffer.toString(Charsets.UTF_8)
}

class IotBridgeConfigSpec :
    FunSpec({
        test("Kafka とレジストリは必須。MQTT のユーザー・クライアント ID・ポートには既定がある") {
            val config = IotBridgeConfig.fromEnvironment(REQUIRED).ok()
            config.mqttHost shouldBe null
            config.mqttPort shouldBe 1883
            config.mqttClientId shouldBe "iot-bridge"
            config.mqttUsername shouldBe "iot-bridge"
            config.receiveMaximum shouldBe 100
            config.healthPort shouldBe 8081

            (IotBridgeConfig.fromEnvironment(emptyMap()) as Result.Err).error.violations.map { it.field } shouldContainExactlyInAnyOrder
                listOf(IotBridgeConfig.KAFKA_BOOTSTRAP, IotBridgeConfig.SCHEMA_REGISTRY_URL)
        }

        test("数の範囲を外れた値は拒否する(値はメッセージに入れない)") {
            val env =
                REQUIRED +
                    mapOf(
                        "IOT_BRIDGE_MQTT_PORT" to "0",
                        "IOT_BRIDGE_MQTT_RECEIVE_MAXIMUM" to "70000",
                        "IOT_BRIDGE_HEALTH_PORT" to "x",
                    )
            val violations = (IotBridgeConfig.fromEnvironment(env) as Result.Err).error.violations
            violations.map { it.field } shouldContainExactlyInAnyOrder
                listOf("IOT_BRIDGE_MQTT_PORT", "IOT_BRIDGE_MQTT_RECEIVE_MAXIMUM", "IOT_BRIDGE_HEALTH_PORT")
            violations.none { it.reason.contains("70000") } shouldBe true
        }

        test("serve は MQTT のホストを要る。購読の設定にはパスワードを出さない") {
            val config = IotBridgeConfig.fromEnvironment(REQUIRED + (IotBridgeConfig.MQTT_HOST to "mosquitto")).ok()
            val settings = config.subscriberSettings(Secret("pw-bridge")).ok()
            settings.host shouldBe "mosquitto"
            settings.toString().contains("pw-bridge") shouldBe false

            (
                IotBridgeConfig
                    .fromEnvironment(
                        REQUIRED,
                    ).ok()
                    .subscriberSettings(Secret("pw")) as Result.Err
            ).error.violations.single().field shouldBe
                IotBridgeConfig.MQTT_HOST
        }

        test("コマンド: 知らないコマンド・serve の設定の不足(MQTT のホストとパスワード)は 2") {
            run(emptyList()).first shouldBe IotBridgeCommands.USAGE
            run(listOf("serve")).first shouldBe IotBridgeCommands.USAGE
            run(listOf("serve"), REQUIRED + (IotBridgeConfig.MQTT_HOST to "mosquitto")).first shouldBe IotBridgeCommands.USAGE
        }

        test("reprocess-dlq: 引数の誤り(--topic を含む)と設定の不足は、接続せずに 2。使い方を出す") {
            run(listOf("reprocess-dlq")).let { (code, output) ->
                code shouldBe 2
                output shouldContain "--limit"
                output shouldContain "使い方: iot-bridge reprocess-dlq"
            }
            run(listOf("reprocess-dlq", "--limit", "5", "--topic", "orders.dlq")).second shouldContain "指定できません"
            run(listOf("reprocess-dlq", "--limit", "5"), emptyMap()).let { (code, output) ->
                code shouldBe 2
                output shouldContain IotBridgeConfig.KAFKA_BOOTSTRAP
            }
        }
    })
