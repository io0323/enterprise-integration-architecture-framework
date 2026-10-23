package io.eia.tools.contract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

private const val MQTT_CONTRACT = "contracts/asyncapi/device-telemetry.v1.yaml"
private const val TELEMETRY_V1 = "contracts/devices/telemetry.v1.schema.json"
private const val TELEMETRY_V2 = "contracts/devices/telemetry.v2.schema.json"
private const val IOT_001 = "contracts/catalog/INT-IOT-001.yaml"

/** MQTT の契約(デバイスと Broker の間)とデバイスの形式のスキーマ(ADR-0039)。 */
class MqttContractSpec :
    FunSpec({
        test("MQTT の契約には Kafka の規則(Topic の命名・CloudEvents のヘッダ・Avro の payload)を当てない") {
            val result = Workspace.ofRepositoryContracts().check()

            result.violations.filter { it.file == MQTT_CONTRACT }.shouldBeEmpty()
        }

        test("MQTT のトピックは devices/{tenant}/{deviceId}/{channel}(CC-NAMING-011)") {
            val result =
                Workspace
                    .ofRepositoryContracts()
                    .replace(MQTT_CONTRACT, "address: devices/{tenant}/{deviceId}/telemetry", "address: devices/{deviceId}/telemetry")
                    .replace(IOT_001, "  - devices/{tenant}/{deviceId}/telemetry", "  - devices/{deviceId}/telemetry")
                    .check()

            result.ruleIds shouldBe setOf("CC-NAMING-011")
        }

        test("MQTT のヘッダ(ユーザープロパティ)には traceparent と correlationid を定義する(CC-MQTT-001)") {
            val result =
                Workspace
                    .ofRepositoryContracts()
                    .replace(MQTT_CONTRACT, "        traceparent:\n          type: string\n", "        other:\n          type: string\n")
                    .check()

            result.ruleIds shouldBe setOf("CC-MQTT-001")
        }

        test("MQTT の payload は contracts/devices の形式のスキーマを参照する(CC-MQTT-002)") {
            val result =
                Workspace
                    .ofRepositoryContracts()
                    .replace(
                        MQTT_CONTRACT,
                        "\$ref: \"../devices/telemetry.v1.schema.json\"",
                        "\$ref: \"../avro/iot/TelemetryReported.avsc\"",
                    ).check()

            // 参照されなくなった版 1 のスキーマも、置いておく理由がないので違反
            result.ruleIds shouldBe setOf("CC-MQTT-002")
            result.violations.map { it.file }.toSet() shouldBe setOf(MQTT_CONTRACT, TELEMETRY_V1)
        }

        test("形式のスキーマは v(ファイル名の版と同じ const)・boot・seq を必須にする(CC-MQTT-002)") {
            val withoutBoot =
                Workspace
                    .ofRepositoryContracts()
                    .replace(
                        TELEMETRY_V2,
                        "\"required\": [\"v\", \"boot\", \"seq\", \"readings\"]",
                        "\"required\": [\"v\", \"seq\", \"readings\"]",
                    ).check()
            withoutBoot.ruleIds shouldBe setOf("CC-MQTT-002")

            val wrongVersion = Workspace.ofRepositoryContracts().replace(TELEMETRY_V2, "\"const\": 2", "\"const\": 1").check()
            wrongVersion.ruleIds shouldBe setOf("CC-MQTT-002")
        }

        test("contracts/devices のファイル名は {message}.v{n}.schema.json(CC-NAMING-012)") {
            val result = Workspace.ofRepositoryContracts().write("contracts/devices/notes.txt", "x").check()

            result.ruleIds shouldBe setOf("CC-NAMING-012")
        }

        test("MQTT の連携は channels が必須(CC-CATALOG-001)") {
            val result =
                Workspace
                    .ofRepositoryContracts()
                    .replace(IOT_001, "channels:\n  - devices/{tenant}/{deviceId}/telemetry\n", "")
                    .check()

            result.ruleIds shouldContain "CC-CATALOG-001"
        }

        context("出したデバイスの形式は変えない(CC-COMPAT-004)") {
            val narrowed = { ws: Workspace -> ws.replace(TELEMETRY_V1, "\"maximum\": 100\n", "\"maximum\": 99\n") }

            test("active の連携の形式を変えると違反") {
                val base = Workspace.ofRepositoryContracts().setLifecycle(IOT_001, "lifecycle: active")
                val result = narrowed(Workspace.ofRepositoryContracts().setLifecycle(IOT_001, "lifecycle: active")).check(baseline = base)

                result.ruleIds shouldBe setOf("CC-COMPAT-004")
            }

            test("設計中なら許可(ADR-0013 §7)") {
                val base = Workspace.ofRepositoryContracts().setLifecycle(IOT_001, "lifecycle: design")
                val result = narrowed(Workspace.ofRepositoryContracts().setLifecycle(IOT_001, "lifecycle: design")).check(baseline = base)

                result.violations.shouldBeEmpty()
                result.designAllowed.map { it.rule.id }.toSet() shouldBe setOf("CC-COMPAT-004")
            }

            test("説明の文だけの変更は違反にしない(項目の名前が description でも比べる)") {
                val base = Workspace.ofRepositoryContracts().setLifecycle(IOT_001, "lifecycle: active")
                val described =
                    Workspace
                        .ofRepositoryContracts()
                        .setLifecycle(IOT_001, "lifecycle: active")
                        .replace(TELEMETRY_V1, "\"description\": \"温度(℃)\"", "\"description\": \"温度(摂氏)\"")
                described.check(baseline = base).violations.shouldBeEmpty()

                val renamed =
                    Workspace
                        .ofRepositoryContracts()
                        .setLifecycle(IOT_001, "lifecycle: active")
                        .replace(TELEMETRY_V1, "\"temp\": {", "\"description\": {")
                renamed.check(baseline = base).ruleIds shouldBe setOf("CC-COMPAT-004")
            }

            test("active の連携の版をやめる(契約から外して消す)のも違反(例外の承認が要る。ADR-0039 §4)") {
                val base = Workspace.ofRepositoryContracts().setLifecycle(IOT_001, "lifecycle: active")
                val contract =
                    Workspace.REPOSITORY_ROOT
                        .resolve(MQTT_CONTRACT)
                        .toFile()
                        .readText()
                val v1Message = checkNotNull(Regex("(?s)    TelemetryV1:.*?(?=    TelemetryV2:)").find(contract)).value
                val result =
                    Workspace
                        .ofRepositoryContracts()
                        .setLifecycle(IOT_001, "lifecycle: active")
                        .replace(MQTT_CONTRACT, "      telemetryV1:\n        \$ref: \"#/components/messages/TelemetryV1\"\n", "")
                        .replace(MQTT_CONTRACT, "      - \$ref: \"#/channels/deviceTelemetry/messages/telemetryV1\"\n", "")
                        .replace(MQTT_CONTRACT, v1Message, "")
                        .delete(TELEMETRY_V1)
                        .check(baseline = base)

                result.ruleIds shouldBe setOf("CC-COMPAT-004")
                result.violations.single().file shouldBe TELEMETRY_V1
            }
        }
    })
