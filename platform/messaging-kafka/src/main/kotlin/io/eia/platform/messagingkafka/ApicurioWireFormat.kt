package io.eia.platform.messagingkafka

import io.eia.platform.schemaregistry.ContentId
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.nio.ByteBuffer

/**
 * スキーマ ID をペイロードの先頭に埋め込む形式(ADR-0007 §1・ADR-0025 §1)。
 * Apicurio Registry 3 の公式の Serde の既定(`Default4ByteIdHandler`・`use-id=contentId`・ヘッダなし)と同じで、Confluent の形式とも同じ。
 *
 * ```
 * [0x00][contentId: 4 バイト・ビッグエンディアン][Avro のバイナリ(単一のデータ。コンテナ形式ではない)]
 * ```
 * ヘッダに ID を入れる形式は使わない。Outbox 経由では、シリアライザが付けたヘッダが Kafka に届かないため。
 */
public object ApicurioWireFormat {
    public const val MAGIC_BYTE: Byte = 0x0
    private const val ID_SIZE = 4
    public const val HEADER_SIZE: Int = 1 + ID_SIZE

    public fun frame(
        contentId: ContentId,
        avroBinary: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_SIZE + avroBinary.size)
            .put(MAGIC_BYTE)
            .putInt(contentId.value.toInt())
            .put(avroBinary)
            .array()

    /** 先頭の ID と、残りの Avro のバイナリに分ける。 */
    public fun parse(payload: ByteArray): Result<Framed, MalformedEventPayload> =
        when {
            payload.size < HEADER_SIZE -> {
                err(MalformedEventPayload("ペイロードが短すぎます(${payload.size} バイト)"))
            }

            payload[0] != MAGIC_BYTE -> {
                err(MalformedEventPayload("先頭のバイトが 0x00 ではありません"))
            }

            else -> {
                val id = ByteBuffer.wrap(payload, 1, ID_SIZE).int
                if (id < 0) {
                    err(MalformedEventPayload("contentId が負の値です"))
                } else {
                    ok(Framed(ContentId(id.toLong()), payload.copyOfRange(HEADER_SIZE, payload.size)))
                }
            }
        }

    public class Framed(
        public val contentId: ContentId,
        public val avroBinary: ByteArray,
    )
}
