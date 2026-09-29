package io.eia.platform.audit

import io.eia.platform.observability.logging.Masking
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [AuditEvent] を検査し、保存する形([AuditRecord] の欄の値)に直す。
 *
 * - details の値は必ず [Masking.mask] を通す(ADR-0018 §3)。キーは決まった形だけを許し、件数と値の長さ(伏せた後)に上限を設ける。
 * - 識別子の欄(actor・target・destination・payloadRef)は伏せない。制御文字と長さだけを検査する。
 *   ここに個人情報や本文を入れないのは呼び出し側の責務(参照キーを入れる。Framework 14.1)。
 * - 時刻はマイクロ秒に切り捨てる(PostgreSQL の timestamptz は丸めるため、切り捨ててから保存して、保存された値とハッシュの入力を一致させる)。
 */
internal object AuditEventNormalizer {
    const val MAX_DETAILS: Int = 32
    const val MAX_DETAIL_VALUE_LENGTH: Int = 1024
    const val MAX_ID_LENGTH: Int = 256
    const val MAX_DESTINATION_LENGTH: Int = 512
    const val MAX_PAYLOAD_REF_LENGTH: Int = 1024

    private val NAME = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    private val DETAIL_KEY = Regex("^[a-z][a-z0-9_.-]{0,63}$")

    /** PostgreSQL の timestamptz で表せて、UTC のエポックからのマイクロ秒が Long に収まる範囲に絞る。 */
    private val MIN_INSTANT: Instant = Instant.parse("0001-01-01T00:00:00Z")
    private val MAX_INSTANT: Instant = Instant.parse("9999-12-31T23:59:59.999999Z")

    data class Normalized(
        val occurredAt: Instant,
        val actorType: String,
        val actorId: String,
        val action: String,
        val targetType: String,
        val targetId: String?,
        val destination: String?,
        val outcome: String,
        val payloadSha256: String?,
        val payloadRef: String?,
        val correlationId: String?,
        val traceparent: String?,
        val details: Map<String, String>,
    )

    @Suppress("ReturnCount") // 検査の段ごとに、最初の違反で返す
    fun normalize(event: AuditEvent): Result<Normalized, InvalidAuditEvent> {
        val problem =
            checkInstant("occurredAt", event.occurredAt)
                ?: checkName("action", event.action)
                ?: checkName("target.type", event.target.type)
                ?: checkText("actor.id", event.actor.id, MAX_ID_LENGTH, required = true)
                ?: checkText("target.id", event.target.id, MAX_ID_LENGTH, required = false)
                ?: checkText("destination", event.destination, MAX_DESTINATION_LENGTH, required = false)
                ?: checkText("payloadRef", event.payloadRef, MAX_PAYLOAD_REF_LENGTH, required = false)
                ?: checkDetailKeys(event.details)
        if (problem != null) return err(problem)
        val details = event.details.mapValues { (_, value) -> Masking.mask(value) }
        details.entries.firstOrNull { (_, value) -> value.length > MAX_DETAIL_VALUE_LENGTH || value.hasForbiddenChar() }?.let { (key, _) ->
            return err(
                InvalidAuditEvent(
                    "details.$key",
                    "伏せた後の値が $MAX_DETAIL_VALUE_LENGTH 文字を超えるか NUL を含みます(本文ではなく参照キーを記録してください)",
                ),
            )
        }
        return ok(
            Normalized(
                occurredAt = truncateToMicros(event.occurredAt),
                actorType = event.actor.type.code,
                actorId = event.actor.id,
                action = event.action,
                targetType = event.target.type,
                targetId = event.target.id,
                destination = event.destination,
                outcome = event.outcome.code,
                payloadSha256 = event.payloadSha256?.hex,
                payloadRef = event.payloadRef,
                correlationId = event.correlationId?.value,
                traceparent = event.traceparent?.format(),
                details = details,
            ),
        )
    }

    fun truncateToMicros(instant: Instant): Instant = instant.truncatedTo(ChronoUnit.MICROS)

    fun checkInstant(
        field: String,
        instant: Instant,
    ): InvalidAuditEvent? =
        if (instant < MIN_INSTANT || instant > MAX_INSTANT) InvalidAuditEvent(field, "0001 年から 9999 年の範囲にしてください") else null

    private fun checkName(
        field: String,
        value: String,
    ): InvalidAuditEvent? = if (NAME.matches(value)) null else InvalidAuditEvent(field, "英小文字で始まる英小文字・数字・_ . - の 128 文字以内にしてください")

    private fun checkText(
        field: String,
        value: String?,
        maxLength: Int,
        required: Boolean,
    ): InvalidAuditEvent? =
        when {
            value == null -> null
            required && value.isEmpty() -> InvalidAuditEvent(field, "空にできません")
            value.length > maxLength -> InvalidAuditEvent(field, "$maxLength 文字以内にしてください")
            value.any { Character.isISOControl(it) } -> InvalidAuditEvent(field, "制御文字を含められません")
            else -> null
        }

    private fun checkDetailKeys(details: Map<String, String>): InvalidAuditEvent? =
        when {
            details.size > MAX_DETAILS -> {
                InvalidAuditEvent("details", "$MAX_DETAILS 件以内にしてください")
            }

            else -> {
                details.keys.firstOrNull { !DETAIL_KEY.matches(it) }?.let {
                    InvalidAuditEvent("details", "キーは英小文字で始まる英小文字・数字・_ . - の 64 文字以内にしてください")
                }
            }
        }

    /** PostgreSQL の text と jsonb は NUL を保存できない。 */
    private fun String.hasForbiddenChar(): Boolean = indexOf('\u0000') >= 0
}
