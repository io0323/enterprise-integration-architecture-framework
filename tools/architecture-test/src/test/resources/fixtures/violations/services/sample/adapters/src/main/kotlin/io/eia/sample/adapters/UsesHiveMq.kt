package io.eia.sample.adapters

// 違反: iot-bridge 以外で MQTT の購読のライブラリを使う(ADR-0038 §3。HiveMQ は services/iot-bridge/adapters だけ)
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client

class UsesHiveMq {
    fun client(): Mqtt5Client = Mqtt5Client.builder().build()
}
