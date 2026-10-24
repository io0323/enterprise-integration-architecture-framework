package io.eia.shared.sdk.mqtt

import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Broker との 1 本の接続(MQTT v5 の最小限の実装。ADR-0038 §3)。接続が切れたら作り直す([MqttPublisher])。
 *
 * - CONNECT → CONNACK。Broker の Receive Maximum・Maximum QoS・Maximum Packet Size・Server Keep Alive に従う。
 * - QoS 1 の PUBLISH は PUBACK を待って理由のコードを返す。同時に待つ数は Receive Maximum(と設定の上限)まで。
 * - 送るものがない間は Keep Alive の間隔で PINGREQ を送り、Keep Alive の 1.5 倍の間に何も受け取らなければ切れたとみなす。
 * - 接続が切れたら、PUBACK を待っていた PUBLISH は [MqttUnavailable](`connection_lost`)で失敗する。送り直しは呼び出し側
 *   (同じ `boot`・`seq` のペイロードを新しい PUBLISH として送る。重複は ce_id で取り除く。ADR-0039 §5)。
 */
internal class MqttConnection private constructor(
    private val config: MqttConfig,
    private val socket: Socket,
    private val input: ByteReadChannel,
    private val output: ByteWriteChannel,
    private val selector: SelectorManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeLock = Mutex()
    private val pendingLock = Mutex()
    private val pending = mutableMapOf<Int, CompletableDeferred<InboundPacket.PubAck>>()
    private var nextPacketId = 1
    private var limits = Limits()
    private lateinit var inFlight: Semaphore

    @Volatile
    private var closedBy: MqttUnavailable? = null

    @Volatile
    private var lastReceived = TimeSource.Monotonic.markNow()

    val isOpen: Boolean get() = closedBy == null

    /** QoS 1 なら PUBACK を待つ。理由のコードが 0x80 以上なら [MqttPublishRejected]。 */
    @Suppress("ReturnCount") // 送る前の検査ごとに返す
    suspend fun publish(message: MqttMessage): Result<PublishResult, MqttError> {
        closedBy?.let { return err(it) }
        if (message.qos.value > limits.maximumQos) return err(MqttInvalidMessage("qos_not_supported"))
        // パケット ID の分(2 バイト)を含めた大きさで、Broker の Maximum Packet Size を超えないか確かめる
        if (MqttEncoder.publish(message, packetId = 1).size > limits.maximumPacketSize) return err(MqttInvalidMessage("packet_too_large"))
        if (message.qos == Qos.AT_MOST_ONCE) {
            return send(MqttEncoder.publish(message, packetId = null)).let { if (it == null) ok(PublishResult(null)) else err(it) }
        }
        inFlight.acquire()
        try {
            val (packetId, ack) = register()
            send(MqttEncoder.publish(message, packetId))?.let { return err(it) }
            val received = withTimeoutOrNull(config.ackTimeout) { ack.await() }
            if (received == null) {
                close(MqttUnavailable("ack_timeout"))
                return err(MqttUnavailable("ack_timeout"))
            }
            return interpret(received)
        } catch (e: ConnectionClosed) {
            return err(e.error)
        } finally {
            inFlight.release()
        }
    }

    /** 正常に切断する(DISCONNECT を送ってから閉じる)。 */
    suspend fun disconnect() {
        if (closedBy == null) send(MqttEncoder.disconnect)
        close(MqttUnavailable("disconnected"))
    }

    private suspend fun register(): Pair<Int, CompletableDeferred<InboundPacket.PubAck>> =
        pendingLock.withLock {
            closedBy?.let { throw ConnectionClosed(it) }
            var id = nextPacketId
            while (id in pending) id = next(id)
            nextPacketId = next(id)
            val ack = CompletableDeferred<InboundPacket.PubAck>()
            pending[id] = ack
            id to ack
        }

    private fun interpret(ack: InboundPacket.PubAck): Result<PublishResult, MqttError> =
        when {
            ack.reasonCode < REASON_FAILURE -> ok(PublishResult(ack.reasonCode))
            ack.reasonCode in TRANSIENT_REASONS -> err(MqttUnavailable("server_busy", ack.reasonCode))
            else -> err(MqttPublishRejected(ack.reasonCode, ack.reasonString))
        }

    /** 書き込む。失敗したら接続を閉じてその理由を返す(成功なら null)。 */
    private suspend fun send(bytes: ByteArray): MqttUnavailable? =
        try {
            writeLock.withLock {
                output.writeByteArray(bytes)
                output.flush()
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            MqttUnavailable("connection_lost").also { close(it) }
        }

    private fun start(connAck: InboundPacket.ConnAck) {
        val properties = connAck.properties
        limits =
            Limits(
                maximumQos = properties.maximumQos ?: Qos.AT_LEAST_ONCE.value,
                keepAlive = properties.serverKeepAlive?.seconds ?: config.keepAlive,
                maximumPacketSize = properties.maximumPacketSize ?: MAX_PACKET_SIZE,
            )
        inFlight = Semaphore(minOf(config.maxInFlight, properties.receiveMaximum ?: MqttConfig.UINT16_MAX))
        scope.launch { readLoop() }
        if (limits.keepAlive.isPositive()) scope.launch { keepAliveLoop(limits.keepAlive) }
    }

    private suspend fun readLoop() {
        while (scope.isActive) {
            val packet =
                try {
                    readPacket(input)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    close(MqttUnavailable("connection_lost"))
                    return
                } catch (_: MalformedPacketException) {
                    close(MqttUnavailable("protocol_error"))
                    return
                }
            lastReceived = TimeSource.Monotonic.markNow()
            when (packet) {
                is InboundPacket.PubAck -> pendingLock.withLock { pending.remove(packet.packetId) }?.complete(packet)
                is InboundPacket.PingResp -> Unit
                is InboundPacket.Disconnect -> close(MqttUnavailable("server_disconnected", packet.reasonCode))
                else -> close(MqttUnavailable("protocol_error"))
            }
        }
    }

    private suspend fun keepAliveLoop(keepAlive: Duration) {
        while (scope.isActive) {
            delay(keepAlive / 2)
            if (lastReceived.elapsedNow() > keepAlive * KEEP_ALIVE_GRACE) {
                close(MqttUnavailable("keepalive_timeout"))
                return
            }
            send(MqttEncoder.pingReq)
        }
    }

    private suspend fun close(reason: MqttUnavailable) {
        val waiting =
            pendingLock.withLock {
                if (closedBy != null) return
                closedBy = reason
                pending.values.toList().also { pending.clear() }
            }
        waiting.forEach { it.completeExceptionally(ConnectionClosed(reason)) }
        socket.close()
        selector.close()
        scope.cancel()
    }

    private class Limits(
        val maximumQos: Int = Qos.AT_LEAST_ONCE.value,
        val keepAlive: Duration = Duration.ZERO,
        val maximumPacketSize: Long = MAX_PACKET_SIZE,
    )

    /** 接続が閉じたことを、PUBACK を待つ呼び出しに伝える。 */
    private class ConnectionClosed(
        val error: MqttUnavailable,
    ) : IllegalStateException(error.reason)

    companion object {
        private const val REASON_FAILURE = 0x80
        private const val KEEP_ALIVE_GRACE = 1.5

        /** Broker が Maximum Packet Size を返さないときの上限(MQTT v5 の固定ヘッダの最大: 1 + 4 + 268,435,455)。 */
        private const val MAX_PACKET_SIZE = 268_435_460L

        /** 一時的な理由のコード(Server unavailable・Server busy・Quota exceeded・Connection rate exceeded)。 */
        private val TRANSIENT_REASONS = setOf(0x88, 0x89, 0x97, 0x9F)

        /** 接続して CONNACK を待つ。拒否は [MqttConnectRejected](一時的な理由なら [MqttUnavailable])。 */
        @Suppress("ReturnCount") // 接続の段ごとに、最初の失敗で返す
        suspend fun open(config: MqttConfig): Result<MqttConnection, MqttError> {
            val selector = SelectorManager(Dispatchers.Default)
            val connected =
                withTimeoutOrNull(config.connectTimeout) {
                    try {
                        val socket = aSocket(selector).tcp().connect(config.host, config.port)
                        val connection =
                            MqttConnection(config, socket, socket.openReadChannel(), socket.openWriteChannel(autoFlush = false), selector)
                        connection.send(MqttEncoder.connect(config))?.let { return@withTimeoutOrNull err(it) }
                        when (val packet = readPacket(connection.input)) {
                            is InboundPacket.ConnAck -> {
                                connection.accept(packet)
                            }

                            else -> {
                                MqttUnavailable("protocol_error").let {
                                    connection.close(it)
                                    err(it)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        err(MqttUnavailable("connect_failed"))
                    } catch (_: MalformedPacketException) {
                        err(MqttUnavailable("protocol_error"))
                    }
                }
            if (connected !is Result.Ok) selector.close()
            return connected ?: err(MqttUnavailable("connect_timeout"))
        }

        private suspend fun MqttConnection.accept(connAck: InboundPacket.ConnAck): Result<MqttConnection, MqttError> {
            if (connAck.reasonCode >= REASON_FAILURE) {
                close(MqttUnavailable("connect_rejected", connAck.reasonCode))
                return err(
                    if (connAck.reasonCode in TRANSIENT_REASONS) {
                        MqttUnavailable("server_busy", connAck.reasonCode)
                    } else {
                        MqttConnectRejected(connAck.reasonCode)
                    },
                )
            }
            start(connAck)
            return ok(this)
        }

        private fun next(id: Int): Int = if (id >= MqttConfig.UINT16_MAX) 1 else id + 1
    }
}

/** 固定ヘッダ(1 バイト + 残りの長さ)と本文を読む。 */
internal suspend fun readPacket(input: ByteReadChannel): InboundPacket {
    val first = input.readByte().toInt() and BYTE_MASK
    var length = 0
    var multiplier = 1
    var digits = 0
    do {
        val digit = input.readByte().toInt() and BYTE_MASK
        length += (digit and VALUE_MASK) * multiplier
        multiplier *= CONTINUATION
        digits++
        if (digits > MAX_LENGTH_DIGITS) malformed("残りの長さが 4 バイトを超えます")
    } while (digit and CONTINUATION != 0)
    val body = if (length == 0) ByteArray(0) else input.readByteArray(length)
    return MqttDecoder.decode(first shr TYPE_SHIFT, first and FLAGS_MASK, body)
}

private const val BYTE_MASK = 0xFF
private const val VALUE_MASK = 0x7F
private const val CONTINUATION = 0x80
private const val MAX_LENGTH_DIGITS = 4
private const val TYPE_SHIFT = 4
private const val FLAGS_MASK = 0x0F
