package io.eia.legacyorderacl.adapters.inbound

import io.eia.legacyorderacl.application.port.inbound.ChangePosition
import io.eia.legacyorderacl.application.port.inbound.LegacyOrderChange
import io.eia.legacyorderacl.domain.LegacyOrderRow
import io.eia.legacyorderacl.domain.TranslationError
import io.eia.legacyorderacl.domain.TranslationFailure
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Instant

/**
 * Debezium の Envelope を、application の [LegacyOrderChange] にする(ADR-0026 §3)。
 * c / u / r は変更の後の行の Upsert、d は変更の前の行の注文番号の Delete。形が合わなければ `UNDECODABLE`。
 */
internal object RawChangeMapper {
    private const val NOT_SNAPSHOT = "false"

    /** LSN を持たない Snapshot のレコードの位置。 */
    const val SNAPSHOT_WITHOUT_LSN: Long = 0L

    fun toChange(envelope: RawJuchuEnvelope): Result<LegacyOrderChange, TranslationError> {
        val snapshot = envelope.source.snapshot != null && envelope.source.snapshot != NOT_SNAPSHOT
        // Incremental Snapshot で読んだレコードは、LSN を持たないことがある(Debezium の仕様)。Snapshot は今の状態の読み直しで、
        // 位置で比べるものではないので、位置 0 で受け入れる(契約の source.lsn。ADR-0026 §6)。ストリーミングの変更に LSN がなければ読めない
        val lsn =
            envelope.source.lsn ?: if (snapshot) SNAPSHOT_WITHOUT_LSN else return err(undecodable("source.lsn", "変更の位置(LSN)がない"))
        val position =
            ChangePosition(
                lsn = lsn,
                committedAt =
                    envelope.source.committedAtMicros?.let(::instantOfMicros)
                        ?: Instant.fromEpochMilliseconds(envelope.source.committedAtMillis),
                snapshot = snapshot,
            )
        return when (envelope.op) {
            "c", "u", "r" -> {
                envelope.after?.let { ok(LegacyOrderChange.Upsert(it.toRow(), position)) }
                    ?: err(undecodable("after", "op=${envelope.op} に変更の後の行がない"))
            }

            "d" -> {
                envelope.before?.let { ok(LegacyOrderChange.Delete(it.orderNumber, position)) }
                    ?: err(undecodable("before", "op=d に変更の前の行がない(REPLICA IDENTITY FULL を確かめる)"))
            }

            else -> {
                err(undecodable("op", "未知の op"))
            }
        }
    }

    private fun RawJuchuRow.toRow(): LegacyOrderRow =
        LegacyOrderRow(
            orderNumber = orderNumber,
            statusCode = statusCode,
            customerName = customerName,
            customerCode = customerCode,
            amount = amount,
            orderedAtLocalMicros = orderedAtLocalMicros,
            updatedAtLocalMicros = updatedAtLocalMicros,
        )

    private fun instantOfMicros(micros: Long): Instant =
        Instant.fromEpochSeconds(micros.floorDiv(MICROS_PER_SECOND), micros.mod(MICROS_PER_SECOND) * NANOS_PER_MICRO)

    private fun undecodable(
        field: String,
        rule: String,
    ) = TranslationError(TranslationFailure.UNDECODABLE, field, rule)

    private const val MICROS_PER_SECOND = 1_000_000L
    private const val NANOS_PER_MICRO = 1_000L
}
