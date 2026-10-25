package io.eia.iotbridge.adapters.inbound

import io.eia.iotbridge.domain.Telemetry
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * ce_id(ADR-0039 §5)。メッセージの名前 `{tenant}/{deviceId}/{boot}/{seq}` から作る UUID の版 5(RFC 9562 §5.5。SHA-1 は識別子を作るためだけに使う)。
 * 同じメッセージ(QoS 1 の再送・iot-bridge の送り直し)は何度届いても同じ ce_id になり、消費者が platform/inbox で取り除ける。
 */
public object MessageIds {
    /** iot-bridge の ce_id の名前空間(ADR-0039 §5。変えると、同じメッセージの ce_id が変わる)。 */
    public val NAMESPACE: UUID = UUID.fromString("692dfaa8-f3ab-483e-b518-69e94388a16b")

    private const val VERSION_5 = 0x50
    private const val VERSION_MASK = 0x0F
    private const val VARIANT_RFC = 0x80
    private const val VARIANT_MASK = 0x3F
    private const val UUID_BYTES = 16
    private const val VERSION_INDEX = 6
    private const val VARIANT_INDEX = 8

    public fun of(telemetry: Telemetry): UUID = nameBased(NAMESPACE, telemetry.identity)

    /** 名前に基づく UUID の版 5。 */
    public fun nameBased(
        namespace: UUID,
        name: String,
    ): UUID {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(
            ByteBuffer
                .allocate(UUID_BYTES)
                .putLong(namespace.mostSignificantBits)
                .putLong(namespace.leastSignificantBits)
                .array(),
        )
        digest.update(name.toByteArray(Charsets.UTF_8))
        val hash = digest.digest().copyOf(UUID_BYTES)
        hash[VERSION_INDEX] = ((hash[VERSION_INDEX].toInt() and VERSION_MASK) or VERSION_5).toByte()
        hash[VARIANT_INDEX] = ((hash[VARIANT_INDEX].toInt() and VARIANT_MASK) or VARIANT_RFC).toByte()
        val buffer = ByteBuffer.wrap(hash)
        return UUID(buffer.long, buffer.long)
    }
}
