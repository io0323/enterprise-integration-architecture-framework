@file:Suppress("MagicNumber") // MQTT v5 の固定ヘッダ・フラグ・プロパティの識別子(OASIS MQTT Version 5.0 §2・§3)

package io.eia.shared.sdk.mqtt

/** MQTT v5 の形式の誤り(長さ・値の範囲・知らないプロパティ)。 */
internal class MalformedPacketException(
    reason: String,
) : IllegalArgumentException(reason)

/** 形式の誤りを投げる(関数ごとの throw の数を増やさないため、ここに集める)。 */
internal fun malformed(reason: String): Nothing = throw MalformedPacketException(reason)

/** Broker から受け取るパケット(最小限の実装が扱うものだけ。ADR-0038)。 */
internal sealed interface InboundPacket {
    data class ConnAck(
        val sessionPresent: Boolean,
        val reasonCode: Int,
        val properties: Properties,
    ) : InboundPacket

    data class PubAck(
        val packetId: Int,
        val reasonCode: Int,
        val reasonString: String?,
    ) : InboundPacket

    data object PingResp : InboundPacket

    data class Disconnect(
        val reasonCode: Int,
    ) : InboundPacket

    /** 最小限の実装が扱わないパケット(購読しないので PUBLISH は来ない。AUTH は使わない)。 */
    data class Unexpected(
        val type: Int,
    ) : InboundPacket
}

/** CONNACK で使うプロパティ(ほかは読み飛ばす)。 */
internal data class Properties(
    val receiveMaximum: Int? = null,
    val maximumQos: Int? = null,
    val retainAvailable: Boolean? = null,
    val maximumPacketSize: Long? = null,
    val serverKeepAlive: Int? = null,
    val reasonString: String? = null,
)

/** 送るパケットの組み立て(ADR-0038 の最小限: CONNECT・PUBLISH・PINGREQ・DISCONNECT)。 */
internal object MqttEncoder {
    private const val PROTOCOL_LEVEL_5 = 5
    private const val CONNECT = 0x10
    private const val PUBLISH = 0x30
    private const val FLAG_USERNAME = 0x80
    private const val FLAG_PASSWORD = 0x40
    private const val FLAG_CLEAN_START = 0x02
    private const val PROP_PAYLOAD_FORMAT = 0x01
    private const val PROP_CONTENT_TYPE = 0x03
    private const val PROP_SESSION_EXPIRY = 0x11
    private const val PROP_USER_PROPERTY = 0x26
    private const val PAYLOAD_UTF8 = 1

    fun connect(config: MqttConfig): ByteArray {
        val properties =
            PacketWriter().apply {
                if (config.sessionExpiry.isPositive()) {
                    byte(PROP_SESSION_EXPIRY)
                    int32(config.sessionExpiry.inWholeSeconds)
                }
            }
        val body =
            PacketWriter().apply {
                string("MQTT")
                byte(PROTOCOL_LEVEL_5)
                var flags = 0
                if (config.cleanStart) flags = flags or FLAG_CLEAN_START
                if (config.username != null) flags = flags or FLAG_USERNAME
                if (config.password != null) flags = flags or FLAG_PASSWORD
                byte(flags)
                int16(config.keepAlive.inWholeSeconds.toInt())
                properties(properties)
                string(config.clientId)
                config.username?.let(::string)
                config.password?.let { binary(it.reveal().encodeToByteArray()) }
            }
        return packet(CONNECT, body)
    }

    fun publish(
        message: MqttMessage,
        packetId: Int?,
    ): ByteArray {
        val properties =
            PacketWriter().apply {
                message.contentType?.let {
                    byte(PROP_PAYLOAD_FORMAT)
                    byte(PAYLOAD_UTF8)
                    byte(PROP_CONTENT_TYPE)
                    string(it)
                }
                message.userProperties.forEach {
                    byte(PROP_USER_PROPERTY)
                    string(it.name)
                    string(it.value)
                }
            }
        val body =
            PacketWriter().apply {
                string(message.topic)
                packetId?.let(::int16)
                properties(properties)
                bytes(message.payload)
            }
        // DUP = 0(再接続の後に送り直すときも新しい PUBLISH として送る。ADR-0038 §3)、retain = 0
        return packet(PUBLISH or (message.qos.value shl 1), body)
    }

    val pingReq: ByteArray = byteArrayOf(0xC0.toByte(), 0)

    /** 正常な切断(理由のコード 0x00。残りの長さ 0 で省略できる)。 */
    val disconnect: ByteArray = byteArrayOf(0xE0.toByte(), 0)

    private fun packet(
        firstByte: Int,
        body: PacketWriter,
    ): ByteArray =
        PacketWriter()
            .apply {
                byte(firstByte)
                varInt(body.size)
                bytes(body.toByteArray())
            }.toByteArray()
}

/** 受け取ったパケットの解釈。[type] は固定ヘッダの上位 4 ビット、[flags] は下位 4 ビット、[body] は可変ヘッダとペイロード。 */
internal object MqttDecoder {
    fun decode(
        type: Int,
        flags: Int,
        body: ByteArray,
    ): InboundPacket {
        val reader = PacketReader(body)
        return when (type) {
            2 -> connAck(reader, flags)
            4 -> pubAck(reader, flags)
            13 -> pingResp(reader, flags)
            14 -> disconnect(reader, flags)
            else -> InboundPacket.Unexpected(type)
        }
    }

    private fun connAck(
        reader: PacketReader,
        flags: Int,
    ): InboundPacket.ConnAck {
        requireFlags(flags, 0)
        val acknowledge = reader.byte()
        if (acknowledge and 0xFE != 0) malformed("CONNACK の予約ビットが 0 ではありません")
        val reasonCode = reader.byte()
        val properties = if (reader.remaining > 0) reader.properties() else Properties()
        reader.requireEnd()
        return InboundPacket.ConnAck(acknowledge and 1 == 1, reasonCode, properties)
    }

    private fun pubAck(
        reader: PacketReader,
        flags: Int,
    ): InboundPacket.PubAck {
        requireFlags(flags, 0)
        val packetId = reader.int16()
        // 残りの長さが 2 なら理由のコードは 0x00(Success)。3 なら理由のコードだけ(MQTT v5 §3.4.2.1)
        val reasonCode = if (reader.remaining > 0) reader.byte() else 0
        val properties = if (reader.remaining > 0) reader.properties() else Properties()
        reader.requireEnd()
        return InboundPacket.PubAck(packetId, reasonCode, properties.reasonString)
    }

    private fun pingResp(
        reader: PacketReader,
        flags: Int,
    ): InboundPacket.PingResp {
        requireFlags(flags, 0)
        reader.requireEnd()
        return InboundPacket.PingResp
    }

    private fun disconnect(
        reader: PacketReader,
        flags: Int,
    ): InboundPacket.Disconnect {
        requireFlags(flags, 0)
        val reasonCode = if (reader.remaining > 0) reader.byte() else 0
        if (reader.remaining > 0) reader.properties()
        reader.requireEnd()
        return InboundPacket.Disconnect(reasonCode)
    }

    private fun requireFlags(
        flags: Int,
        expected: Int,
    ) {
        if (flags != expected) malformed("固定ヘッダのフラグが不正です")
    }
}

/** 可変長の整数(Variable Byte Integer。MQTT v5 §1.5.5)の上限。 */
internal const val MAX_VAR_INT = 268_435_455

/** パケットを組み立てるための書き込み先(ビッグエンディアン)。 */
internal class PacketWriter {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    var size: Int = 0
        private set

    fun byte(value: Int) {
        ensure(1)
        buffer[size++] = value.toByte()
    }

    fun int16(value: Int) {
        require(value in 0..MqttConfig.UINT16_MAX) { "2 バイトの整数の範囲外です" }
        byte(value shr 8)
        byte(value)
    }

    fun int32(value: Long) {
        require(value in 0..MqttConfig.UINT32_MAX) { "4 バイトの整数の範囲外です" }
        byte((value shr 24).toInt())
        byte((value shr 16).toInt())
        byte((value shr 8).toInt())
        byte(value.toInt())
    }

    fun varInt(value: Int) {
        require(value in 0..MAX_VAR_INT) { "可変長の整数の範囲外です" }
        var remaining = value
        do {
            var digit = remaining % 128
            remaining /= 128
            if (remaining > 0) digit = digit or 0x80
            byte(digit)
        } while (remaining > 0)
    }

    fun string(value: String) = binary(value.encodeToByteArray())

    fun binary(value: ByteArray) {
        int16(value.size)
        bytes(value)
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun properties(properties: PacketWriter) {
        varInt(properties.size)
        bytes(properties.toByteArray())
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
    }
}

/** 受け取ったパケットの本文の読み取り。長さが足りなければ [MalformedPacketException]。 */
internal class PacketReader(
    private val bytes: ByteArray,
) {
    private var position = 0
    val remaining: Int get() = bytes.size - position

    fun byte(): Int {
        if (remaining < 1) malformed("長さが足りません")
        return bytes[position++].toInt() and 0xFF
    }

    fun int16(): Int = (byte() shl 8) or byte()

    fun int32(): Long = (int16().toLong() shl 16) or int16().toLong()

    fun varInt(): Int {
        var value = 0
        var multiplier = 1
        repeat(4) {
            val digit = byte()
            value += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) return value
            multiplier *= 128
        }
        malformed("可変長の整数が 4 バイトを超えます")
    }

    fun binary(): ByteArray {
        val length = int16()
        if (remaining < length) malformed("長さが足りません")
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    fun string(): String = binary().decodeToString()

    fun requireEnd() {
        if (remaining != 0) malformed("余分なバイトがあります")
    }

    /** プロパティを読む。使うものだけを取り出し、ほかは型に従って読み飛ばす(MQTT v5 §2.2.2.2)。 */
    @Suppress("CyclomaticComplexMethod") // プロパティの識別子ごとの型(仕様の表のとおり)
    fun properties(): Properties {
        val length = varInt()
        if (remaining < length) malformed("プロパティの長さが足りません")
        val end = position + length
        var result = Properties()
        while (position < end) {
            when (val id = varInt()) {
                0x21 -> {
                    result = result.copy(receiveMaximum = int16())
                }

                0x24 -> {
                    result = result.copy(maximumQos = byte())
                }

                0x25 -> {
                    result = result.copy(retainAvailable = byte() == 1)
                }

                0x27 -> {
                    result = result.copy(maximumPacketSize = int32())
                }

                0x13 -> {
                    result = result.copy(serverKeepAlive = int16())
                }

                0x1F -> {
                    result = result.copy(reasonString = string())
                }

                0x01, 0x17, 0x19, 0x28, 0x29, 0x2A -> {
                    byte()
                }

                0x22, 0x23 -> {
                    int16()
                }

                0x02, 0x11, 0x18 -> {
                    int32()
                }

                0x0B -> {
                    varInt()
                }

                0x03, 0x08, 0x12, 0x15, 0x1A, 0x1C -> {
                    string()
                }

                0x09, 0x16 -> {
                    binary()
                }

                0x26 -> {
                    string()
                    string()
                }

                else -> {
                    malformed("知らないプロパティの識別子です: 0x${id.hex()}")
                }
            }
        }
        if (position != end) malformed("プロパティの長さが合いません")
        return result
    }
}
