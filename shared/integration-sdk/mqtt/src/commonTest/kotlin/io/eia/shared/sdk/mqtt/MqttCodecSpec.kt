package io.eia.shared.sdk.mqtt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private fun varInt(value: Int): ByteArray = PacketWriter().apply { varInt(value) }.toByteArray()

class MqttCodecSpec :
    FunSpec({
        context("可変長の整数(MQTT v5 §1.5.5)") {
            test("境界の値を 1〜4 バイトで表し、読み戻せる") {
                mapOf(
                    0 to bytes(0x00),
                    127 to bytes(0x7F),
                    128 to bytes(0x80, 0x01),
                    16_383 to bytes(0xFF, 0x7F),
                    16_384 to bytes(0x80, 0x80, 0x01),
                    MAX_VAR_INT to bytes(0xFF, 0xFF, 0xFF, 0x7F),
                ).forEach { (value, encoded) ->
                    varInt(value) shouldBe encoded
                    PacketReader(encoded).varInt() shouldBe value
                }
            }

            test("5 バイト目に続くものは形式の誤り") {
                shouldThrow<MalformedPacketException> { PacketReader(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x01)).varInt() }
            }
        }

        test("CONNECT: プロトコル名・版 5・フラグ・Keep Alive・Session Expiry・クライアント ID・資格情報") {
            val config =
                MqttConfig(
                    host = "broker",
                    port = 1883,
                    clientId = "d-1",
                    username = "t1/d-1",
                    password = MqttPassword("pw"),
                    keepAlive = 30.seconds,
                    sessionExpiry = 60.seconds,
                )
            MqttEncoder.connect(config) shouldBe
                bytes(
                    0x10,
                    33,
                    0x00,
                    0x04,
                    'M'.code,
                    'Q'.code,
                    'T'.code,
                    'T'.code,
                    5,
                    0xC2,
                    0x00,
                    30,
                    5,
                    0x11,
                    0x00,
                    0x00,
                    0x00,
                    60,
                    0x00,
                    0x03,
                    'd'.code,
                    '-'.code,
                    '1'.code,
                    0x00,
                    0x06,
                    't'.code,
                    '1'.code,
                    '/'.code,
                    'd'.code,
                    '-'.code,
                    '1'.code,
                    0x00,
                    0x02,
                    'p'.code,
                    'w'.code,
                )
        }

        test("PUBLISH(QoS 1): トピック・パケット ID・Content Type・ユーザープロパティ・ペイロード。DUP と retain は 0") {
            val message =
                MqttMessage(
                    topic = "a/b",
                    payload = "{}".encodeToByteArray(),
                    userProperties = listOf(UserProperty("k", "v")),
                    contentType = "j",
                )
            MqttEncoder.publish(message, packetId = 7) shouldBe
                bytes(
                    0x32,
                    23,
                    0x00,
                    0x03,
                    'a'.code,
                    '/'.code,
                    'b'.code,
                    0x00,
                    0x07,
                    13,
                    0x01,
                    1,
                    0x03,
                    0x00,
                    0x01,
                    'j'.code,
                    0x26,
                    0x00,
                    0x01,
                    'k'.code,
                    0x00,
                    0x01,
                    'v'.code,
                    '{'.code,
                    '}'.code,
                )
        }

        context("受け取るパケット") {
            test("CONNACK: Session Present・理由のコード・使うプロパティを取り出し、ほかの型のプロパティは読み飛ばす") {
                val properties =
                    bytes(
                        0x21,
                        0x00,
                        0x0A,
                        0x24,
                        0x01,
                        0x27,
                        0x00,
                        0x00,
                        0x40,
                        0x00,
                        0x13,
                        0x00,
                        0x3C,
                        0x12,
                        0x00,
                        0x01,
                        'x'.code,
                        0x26,
                        0x00,
                        0x01,
                        'k'.code,
                        0x00,
                        0x01,
                        'v'.code,
                        0x22,
                        0x00,
                        0x05,
                    )
                val body = bytes(0x01, 0x00, properties.size) + properties

                val connAck = MqttDecoder.decode(2, 0, body).shouldBeInstanceOf<InboundPacket.ConnAck>()

                connAck.sessionPresent shouldBe true
                connAck.reasonCode shouldBe 0
                connAck.properties shouldBe
                    Properties(receiveMaximum = 10, maximumQos = 1, maximumPacketSize = 16_384, serverKeepAlive = 60)
            }

            test("PUBACK: 残りの長さが 2 なら Success、3 なら理由のコードだけ、4 以上ならプロパティ(Reason String)も") {
                MqttDecoder.decode(4, 0, bytes(0x00, 0x07)) shouldBe InboundPacket.PubAck(7, 0x00, null)
                MqttDecoder.decode(4, 0, bytes(0x00, 0x07, 0x87)) shouldBe InboundPacket.PubAck(7, 0x87, null)
                MqttDecoder.decode(4, 0, bytes(0x00, 0x07, 0x87, 0x05, 0x1F, 0x00, 0x02, 'n'.code, 'o'.code)) shouldBe
                    InboundPacket.PubAck(7, 0x87, "no")
            }

            test("PINGRESP・DISCONNECT・扱わないパケット") {
                MqttDecoder.decode(13, 0, ByteArray(0)) shouldBe InboundPacket.PingResp
                MqttDecoder.decode(14, 0, ByteArray(0)) shouldBe InboundPacket.Disconnect(0)
                MqttDecoder.decode(14, 0, bytes(0x8B)) shouldBe InboundPacket.Disconnect(0x8B)
                MqttDecoder.decode(3, 0, bytes(0x00, 0x01, 'a'.code)) shouldBe InboundPacket.Unexpected(3)
            }

            test("形式の誤り: 足りない長さ・余分なバイト・知らないプロパティ・不正なフラグ") {
                listOf(
                    { MqttDecoder.decode(4, 0, bytes(0x00)) },
                    { MqttDecoder.decode(13, 0, bytes(0x00)) },
                    { MqttDecoder.decode(2, 0, bytes(0x00, 0x00, 0x02, 0x7F, 0x00)) },
                    { MqttDecoder.decode(4, 2, bytes(0x00, 0x07)) },
                    { MqttDecoder.decode(2, 0, bytes(0x02, 0x00)) },
                ).forEach { shouldThrow<MalformedPacketException> { it() } }
            }
        }

        test("withTrace: traceparent(親の子)と correlationid をユーザープロパティに付け、既にあれば置き換える") {
            val parent =
                io.eia.shared.resilience.trace.TraceParent
                    .generate(kotlin.random.Random(1))
            val message =
                MqttMessage("a/b", ByteArray(0), userProperties = listOf(UserProperty("traceparent", "old"), UserProperty("x", "1")))
                    .withTrace(
                        parent,
                        io.eia.shared.kernel.CorrelationId
                            .generate(kotlin.random.Random(2)),
                    )

            message.userProperties.map { it.name } shouldBe listOf("x", "traceparent", "correlationid")
            val sent = message.userProperties.single { it.name == "traceparent" }.value
            sent.substring(3, 35) shouldBe parent.traceId.toString()
            (sent == parent.format()) shouldBe false
        }

        test("パスワードと設定の toString は値を出さない") {
            MqttPassword("secret").toString() shouldBe "***"
            MqttConfig("h", 1883, "c", "u", MqttPassword("secret")).toString().contains("secret") shouldBe false
        }
    })
