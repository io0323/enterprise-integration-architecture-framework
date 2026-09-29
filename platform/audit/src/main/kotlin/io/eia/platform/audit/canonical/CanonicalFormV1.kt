package io.eia.platform.audit.canonical

import io.eia.platform.audit.AuditRecord
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.Arrays

/**
 * 直列化の版 1(ADR-0017 の表と同じ内容)。
 *
 * - 先頭に [MAGIC](ASCII 10 バイト)と版(u16。ビッグエンディアン)。
 * - 続けて欄を決まった順に並べる。どの欄も「有無のフラグ(u8: 0x00 = NULL、0x01 = 値あり)」の後に、
 *   値ありなら「長さ(u32。ビッグエンディアン。バイト数)+ 値のバイト列」を置く。NULL と空文字列(長さ 0)は区別される。
 * - 文字列は UTF-8(正規化はしない。保存された値のまま)。整数(seq)と時刻は 8 バイトのビッグエンディアンの符号付き整数。
 * - 時刻は UTC の 1970-01-01T00:00:00Z からのマイクロ秒(PostgreSQL の timestamptz の精度)。マイクロ秒未満は負の無限大の向きに切り捨てる
 *   (記録する側が切り捨ててから保存するため、保存された値ではマイクロ秒未満は常に 0)。
 * - details は有無のフラグ(常に 0x01)+ 件数(u32)の後に、キーの UTF-8 のバイト列の符号なしの辞書順で、キーの欄・値の欄を交互に置く。
 *   キーは常に値あり、値は NULL がありうる。
 */
public object CanonicalFormV1 : CanonicalForm {
    override val version: Int = 1

    /** 他の用途のバイト列と取り違えないための接頭辞(ドメイン分離)。 */
    public const val MAGIC: String = "EIAF-AUDIT"

    private const val ABSENT = 0
    private const val PRESENT = 1
    private const val LONG_BYTES = 8
    private const val MICROS_PER_SECOND = 1_000_000L
    private const val NANOS_PER_MICRO = 1_000

    override fun encode(record: AuditRecord): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.writeShort(version)
            out.longField(record.seq)
            out.stringField(record.prevHash)
            out.longField(epochMicros(record.occurredAt))
            out.longField(epochMicros(record.recordedAt))
            out.stringField(record.actorType)
            out.stringField(record.actorId)
            out.stringField(record.action)
            out.stringField(record.targetType)
            out.stringField(record.targetId)
            out.stringField(record.destination)
            out.stringField(record.outcome)
            out.stringField(record.payloadSha256)
            out.stringField(record.payloadRef)
            out.stringField(record.correlationId)
            out.stringField(record.traceparent)
            out.detailsField(record.details)
        }
        return buffer.toByteArray()
    }

    /** UTC のエポックからのマイクロ秒。マイクロ秒未満は切り捨てる(負の時刻でも負の無限大の向き)。 */
    public fun epochMicros(instant: Instant): Long =
        Math.addExact(Math.multiplyExact(instant.epochSecond, MICROS_PER_SECOND), (instant.nano / NANOS_PER_MICRO).toLong())

    private fun DataOutputStream.longField(value: Long) {
        writeByte(PRESENT)
        writeInt(LONG_BYTES)
        writeLong(value)
    }

    private fun DataOutputStream.stringField(value: String?) {
        if (value == null) {
            writeByte(ABSENT)
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeByte(PRESENT)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.detailsField(details: Map<String, String?>) {
        writeByte(PRESENT)
        writeInt(details.size)
        details.entries
            .map { (key, value) -> key.toByteArray(Charsets.UTF_8) to value }
            .sortedWith { a, b -> Arrays.compareUnsigned(a.first, b.first) }
            .forEach { (key, value) ->
                writeByte(PRESENT)
                writeInt(key.size)
                write(key)
                stringField(value)
            }
    }
}
