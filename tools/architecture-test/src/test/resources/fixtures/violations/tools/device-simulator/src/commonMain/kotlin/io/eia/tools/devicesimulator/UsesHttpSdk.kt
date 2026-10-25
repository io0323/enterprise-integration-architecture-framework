package io.eia.tools.devicesimulator

import io.eia.shared.sdk.http.IntegrationClient
import io.eia.shared.sdk.mqtt.MqttPublisher

// 違反: SDK の HTTP の部分を使う(MQTT の部分は許可)
class UsesHttpSdk(
    val http: IntegrationClient,
    val mqtt: MqttPublisher,
)
