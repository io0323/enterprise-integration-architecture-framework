package io.eia.shared.sdk.mqtt

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Jitter
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.RetryPolicy
import io.eia.shared.resilience.Resilience
import io.eia.shared.resilience.ResilienceConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun publisher(broker: FakeBroker) =
    MqttPublisher(
        MqttConfig(host = "127.0.0.1", port = broker.port, clientId = "d-1", username = "t1/d-1", password = MqttPassword("pw")),
        Resilience(
            "mqtt-test",
            ResilienceConfig(
                attemptTimeout = 5.seconds,
                retry = RetryPolicy(initialDelay = 10.milliseconds, maxAttempts = 3, jitter = Jitter.NONE),
                retryBudget = null,
                circuitBreaker = null,
            ),
        ),
    )

private val message = MqttMessage("devices/t1/d-1/telemetry", """{"v":2}""".encodeToByteArray(), contentType = "application/json")

/** 最小限の実装の振る舞いを、テスト用の Broker で確かめる(jvm と native)。実際の Mosquitto との確認は integrationTest。 */
class MqttPublisherSpec :
    FunSpec({
        test("QoS 1 で送り、PUBACK の理由のコードを返す。ユーザープロパティ(traceparent)とペイロードが届く") {
            val broker = FakeBroker(Behaviour()).start()
            val publisher = publisher(broker)
            try {
                publisher.publish(message.withTrace()) shouldBe Result.Ok(PublishResult(0x00))

                val received = broker.publishes().single()
                received.topic shouldBe "devices/t1/d-1/telemetry"
                received.qos shouldBe 1
                received.dup shouldBe false
                received.payload shouldBe """{"v":2}"""
                received.userProperties.map { it.name } shouldBe listOf("traceparent", "correlationid")
            } finally {
                publisher.close()
                broker.stop()
            }
        }

        test("同じ接続を使い回す(送るたびに接続しない)") {
            val broker = FakeBroker(Behaviour()).start()
            val publisher = publisher(broker)
            try {
                repeat(3) { publisher.publish(message).shouldBeInstanceOf<Result.Ok<PublishResult>>() }

                broker.connections shouldBe 1
                broker.publishes().size shouldBe 3
            } finally {
                publisher.close()
                broker.stop()
            }
        }

        test("PUBACK の前に接続が切れたら、再接続して新しい PUBLISH で送り直す(同じペイロード)") {
            val broker = FakeBroker(Behaviour(pubAckReasons = listOf(null)), Behaviour()).start()
            val publisher = publisher(broker)
            try {
                publisher.publish(message) shouldBe Result.Ok(PublishResult(0x00))

                broker.connections shouldBe 2
                broker.publishes().map { it.connection to it.payload } shouldBe listOf(0 to """{"v":2}""", 1 to """{"v":2}""")
            } finally {
                publisher.close()
                broker.stop()
            }
        }

        test("ACL で拒否(PUBACK 0x87 Not authorized)されたら送り直さない(NonRetryable)") {
            val broker = FakeBroker(Behaviour(pubAckReasons = listOf(0x87))).start()
            val publisher = publisher(broker)
            try {
                val error = publisher.publish(message).shouldBeInstanceOf<Result.Err<DomainError>>().error
                error shouldBe MqttPublishRejected(0x87)
                broker.publishes().size shouldBe 1
            } finally {
                publisher.close()
                broker.stop()
            }
        }

        test("資格情報の誤り(CONNACK 0x86)は送り直さない。Broker が混んでいる(0x89)は送り直す") {
            val rejecting = FakeBroker(Behaviour(connAckReason = 0x86)).start()
            try {
                publisher(rejecting).publish(message).shouldBeInstanceOf<Result.Err<DomainError>>().error shouldBe MqttConnectRejected(0x86)
                rejecting.connections shouldBe 1
            } finally {
                rejecting.stop()
            }

            val busy = FakeBroker(Behaviour(connAckReason = 0x89), Behaviour()).start()
            val publisher = publisher(busy)
            try {
                publisher.publish(message) shouldBe Result.Ok(PublishResult(0x00))
                busy.connections shouldBe 2
            } finally {
                publisher.close()
                busy.stop()
            }
        }

        test("Broker につながらなければ、回数の上限まで試して Retryable の失敗を返す") {
            val broker = FakeBroker(Behaviour()).start()
            val port = broker.port
            broker.stop()
            val publisher =
                MqttPublisher(
                    MqttConfig(host = "127.0.0.1", port = port, clientId = "d-1"),
                    Resilience(
                        "mqtt-test",
                        ResilienceConfig(
                            attemptTimeout = 5.seconds,
                            retry = RetryPolicy(initialDelay = 10.milliseconds, maxAttempts = 2, jitter = Jitter.NONE),
                            retryBudget = null,
                            circuitBreaker = null,
                        ),
                    ),
                )

            publisher
                .publish(message)
                .shouldBeInstanceOf<Result.Err<DomainError>>()
                .error
                .shouldBeInstanceOf<DomainError.Retryable>()
        }

        test("Broker の Maximum QoS が 0 なら、QoS 1 のメッセージは送らずに失敗する") {
            val broker = FakeBroker(Behaviour(connAckProperties = byteArrayOf(0x24, 0x00))).start()
            val publisher = publisher(broker)
            try {
                publisher.publish(message).shouldBeInstanceOf<Result.Err<DomainError>>().error shouldBe
                    MqttInvalidMessage("qos_not_supported")
                broker.publishes().size shouldBe 0
            } finally {
                publisher.close()
                broker.stop()
            }
        }

        test("Broker の Maximum Packet Size を超えるメッセージは送らずに失敗する") {
            // Maximum Packet Size = 64 バイト
            val broker = FakeBroker(Behaviour(connAckProperties = byteArrayOf(0x27, 0, 0, 0, 64))).start()
            val publisher = publisher(broker)
            try {
                publisher
                    .publish(MqttMessage("devices/t1/d-1/telemetry", ByteArray(100)))
                    .shouldBeInstanceOf<Result.Err<DomainError>>()
                    .error shouldBe MqttInvalidMessage("packet_too_large")
                broker.publishes().size shouldBe 0
            } finally {
                publisher.close()
                broker.stop()
            }
        }
    })
