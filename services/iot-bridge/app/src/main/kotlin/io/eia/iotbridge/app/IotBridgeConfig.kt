package io.eia.iotbridge.app

import io.eia.iotbridge.adapters.inbound.MqttSubscriberSettings
import io.eia.platform.security.secret.Secret
import io.eia.platform.security.secret.SecretName
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok

/**
 * iot-bridge の設定(環境変数)。パスワードは値ではなく名前([SecretName])だけを持ち、`SecretProvider` から読む。
 * Kafka と Apicurio はローカルでは未認証(ADR-0008 の縮退)。MQTT はブリッジ専用のユーザーで、`devices/+/+/telemetry` の読み取りだけ(ADR-0039 §3)。
 *
 * | 環境変数 | 既定 | 使うコマンド |
 * |---|---|---|
 * | `IOT_BRIDGE_KAFKA_BOOTSTRAP` | なし(必須) | serve・reprocess-dlq(例 `kafka:9092`) |
 * | `IOT_BRIDGE_SCHEMA_REGISTRY_URL` | なし(必須) | serve・reprocess-dlq(例 `http://apicurio:8080/apis/registry/v3`) |
 * | `IOT_BRIDGE_MQTT_HOST` | なし(serve では必須) | serve(例 `mosquitto`) |
 * | `IOT_BRIDGE_MQTT_PORT` | 1883 | serve |
 * | `IOT_BRIDGE_MQTT_CLIENT_ID` | `iot-bridge` | serve(永続セッションの識別子。変えると Broker の溜め込みを引き継げない。§9) |
 * | `IOT_BRIDGE_MQTT_USERNAME` | `iot-bridge` | serve |
 * | `IOT_BRIDGE_MQTT_PASSWORD`(または `_FILE`) | なし(serve では必須) | serve だけ |
 * | `IOT_BRIDGE_MQTT_RECEIVE_MAXIMUM` | 100 | serve(同時に受け取る数。§8 の背圧) |
 * | `IOT_BRIDGE_HEALTH_PORT` | 8081 | serve(`/health/live` と `/health/ready` だけ。平文。コンテナの外に公開しない) |
 */
internal data class IotBridgeConfig(
    val kafkaBootstrap: String,
    val schemaRegistryUrl: String,
    val mqttHost: String?,
    val mqttPort: Int,
    val mqttClientId: String,
    val mqttUsername: String,
    val receiveMaximum: Int,
    val healthPort: Int,
) {
    /** serve に必要な MQTT の設定(ホストとパスワード)を合わせて、購読の設定を作る。 */
    fun subscriberSettings(password: Secret): Result<MqttSubscriberSettings, ValidationError> =
        mqttHost?.let {
            ok(
                MqttSubscriberSettings(
                    host = it,
                    port = mqttPort,
                    clientId = mqttClientId,
                    username = mqttUsername,
                    password = password.reveal(),
                    receiveMaximum = receiveMaximum,
                ),
            )
        } ?: err(ValidationError.of(MQTT_HOST, "serve では必須です"))

    companion object {
        val MQTT_PASSWORD = SecretName("IOT_BRIDGE_MQTT_PASSWORD")
        const val KAFKA_BOOTSTRAP = "IOT_BRIDGE_KAFKA_BOOTSTRAP"
        const val SCHEMA_REGISTRY_URL = "IOT_BRIDGE_SCHEMA_REGISTRY_URL"
        const val MQTT_HOST = "IOT_BRIDGE_MQTT_HOST"
        private const val DEFAULT_MQTT_PORT = 1883
        private const val DEFAULT_HEALTH_PORT = 8081
        private const val DEFAULT_IDENTITY = "iot-bridge"
        private const val MAX_PORT = 65_535
        private const val MAX_RECEIVE = 65_535

        fun fromEnvironment(env: Map<String, String>): Result<IotBridgeConfig, ValidationError> {
            val violations = mutableListOf<FieldViolation>()

            fun optional(name: String) = env[name]?.takeIf { it.isNotBlank() }

            fun required(name: String) = optional(name) ?: "".also { violations += FieldViolation(name, "必須です") }

            fun int(
                name: String,
                default: Int,
                range: IntRange,
            ): Int =
                optional(name)?.let { value ->
                    value.toIntOrNull()?.takeIf { it in range }
                        ?: default.also { violations += FieldViolation(name, "${range.first}〜${range.last} の整数にしてください") }
                } ?: default
            val config =
                IotBridgeConfig(
                    kafkaBootstrap = required(KAFKA_BOOTSTRAP),
                    schemaRegistryUrl = required(SCHEMA_REGISTRY_URL),
                    mqttHost = optional(MQTT_HOST),
                    mqttPort = int("IOT_BRIDGE_MQTT_PORT", DEFAULT_MQTT_PORT, 1..MAX_PORT),
                    mqttClientId = optional("IOT_BRIDGE_MQTT_CLIENT_ID") ?: DEFAULT_IDENTITY,
                    mqttUsername = optional("IOT_BRIDGE_MQTT_USERNAME") ?: DEFAULT_IDENTITY,
                    receiveMaximum =
                        int("IOT_BRIDGE_MQTT_RECEIVE_MAXIMUM", MqttSubscriberSettings.DEFAULT_RECEIVE_MAXIMUM, 1..MAX_RECEIVE),
                    healthPort = int("IOT_BRIDGE_HEALTH_PORT", DEFAULT_HEALTH_PORT, 0..MAX_PORT),
                )
            return if (violations.isEmpty()) ok(config) else err(ValidationError(violations))
        }
    }
}
