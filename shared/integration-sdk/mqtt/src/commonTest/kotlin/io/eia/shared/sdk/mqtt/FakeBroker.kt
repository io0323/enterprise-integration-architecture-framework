package io.eia.shared.sdk.mqtt

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Broker が受け取った PUBLISH。 */
internal data class ReceivedPublish(
    val connection: Int,
    val topic: String,
    val qos: Int,
    val dup: Boolean,
    val userProperties: List<UserProperty>,
    val payload: String,
)

/** 1 本の接続での Broker のふるまい。 */
internal data class Behaviour(
    val connAckReason: Int = 0x00,
    val connAckProperties: ByteArray = ByteArray(0),
    /** 受け取った PUBLISH ごとの PUBACK の理由のコード。null なら PUBACK を返さずに接続を切る。尽きたら 0x00。 */
    val pubAckReasons: List<Int?> = emptyList(),
)

/**
 * テスト用の最小限の MQTT v5 の Broker(127.0.0.1 の空いているポート)。接続ごとに [behaviours] を順に使う(尽きたら最後のもの)。
 * jvm と native のどちらでも動く(ktor-network)。
 */
internal class FakeBroker(
    private vararg val behaviours: Behaviour,
) {
    private val selector = SelectorManager(Dispatchers.Default)

    // 止めた後の accept・切れた接続への書き込みの例外は、テストの Broker では無視する(native では捕まえない例外がプロセスを落とすため)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    private val lock = Mutex()
    private val received = mutableListOf<ReceivedPublish>()
    private lateinit var server: ServerSocket
    var connections: Int = 0
        private set
    val port: Int get() = (server.localAddress as InetSocketAddress).port

    suspend fun publishes(): List<ReceivedPublish> = lock.withLock { received.toList() }

    suspend fun start(): FakeBroker {
        server = aSocket(selector).tcp().bind("127.0.0.1", 0)
        scope.launch {
            while (true) {
                // 止めると accept が失敗する(Linux では EINVAL)。ループを抜ける
                val socket = runCatchingEof { server.accept() } ?: break
                val index = connections++
                val behaviour = behaviours.getOrElse(index) { behaviours.last() }
                scope.launch {
                    val input = socket.openReadChannel()
                    val output = socket.openWriteChannel(autoFlush = true)
                    readRaw(input) // CONNECT
                    output.writeByteArray(connAck(behaviour))
                    if (behaviour.connAckReason >= 0x80) {
                        socket.close()
                        return@launch
                    }
                    var publishes = 0
                    var open = true
                    while (open) {
                        val packet = runCatchingEof { readRaw(input) }
                        val type = packet?.first?.shr(4)
                        when {
                            packet == null || type == 14 -> {
                                open = false
                            }

                            type == 3 -> {
                                val publish = parsePublish(index, packet.first, packet.second)
                                lock.withLock { received += publish.first }
                                val reason = behaviour.pubAckReasons.getOrElse(publishes++) { 0x00 }
                                if (reason == null) {
                                    open = false
                                } else {
                                    publish.second?.let { id -> output.writeByteArray(pubAck(id, reason)) }
                                }
                            }

                            type == 12 -> {
                                output.writeByteArray(byteArrayOf(0xD0.toByte(), 0))
                            }
                        }
                    }
                    socket.close()
                }
            }
        }
        return this
    }

    fun stop() {
        scope.cancel()
        server.close()
        selector.close()
    }

    private fun pubAck(
        id: Int,
        reason: Int,
    ): ByteArray = byteArrayOf(0x40, 3, (id shr 8).toByte(), id.toByte(), reason.toByte())

    private fun connAck(behaviour: Behaviour): ByteArray {
        val body = byteArrayOf(0, behaviour.connAckReason.toByte(), behaviour.connAckProperties.size.toByte()) + behaviour.connAckProperties
        return byteArrayOf(0x20, body.size.toByte()) + body
    }

    private fun parsePublish(
        connection: Int,
        first: Int,
        body: ByteArray,
    ): Pair<ReceivedPublish, Int?> {
        val reader = PacketReader(body)
        val qos = (first shr 1) and 0x03
        val topic = reader.string()
        val packetId = if (qos > 0) reader.int16() else null
        val end = reader.varInt().let { length -> body.size - reader.remaining + length }
        val properties = mutableListOf<UserProperty>()
        while (body.size - reader.remaining < end) {
            when (reader.varInt()) {
                0x01 -> reader.byte()
                0x03 -> reader.string()
                0x26 -> properties += UserProperty(reader.string(), reader.string())
                else -> error("想定外のプロパティ")
            }
        }
        val payload = body.copyOfRange(body.size - reader.remaining, body.size).decodeToString()
        return ReceivedPublish(connection, topic, qos, first and 0x08 != 0, properties, payload) to packetId
    }

    private suspend fun readRaw(input: ByteReadChannel): Pair<Int, ByteArray> {
        val first = input.readByte().toInt() and 0xFF
        var length = 0
        var multiplier = 1
        do {
            val digit = input.readByte().toInt() and 0xFF
            length += (digit and 0x7F) * multiplier
            multiplier *= 128
        } while (digit and 0x80 != 0)
        return first to (if (length == 0) ByteArray(0) else input.readByteArray(length))
    }

    @Suppress("TooGenericExceptionCaught") // 接続が閉じたときの例外の型はエンジンによる(テストの Broker なので区別しない)
    private suspend fun <T> runCatchingEof(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
}
