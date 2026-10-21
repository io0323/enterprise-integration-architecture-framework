package io.eia.webhookreceiver.domain

import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Webhook の受け取りの記録(ADR-0035 §3)。重複の判定と調査に要るものだけを持ち、**本文は持たない**(個人情報の置き場所を増やさない)。
 * 本文は Outbox を通して社内の取り込みのトピックに流すだけにする。
 *
 * @property webhookId 送り手が付けたイベント ID(`webhook-id`。再送でも同じ)。重複の判定のキー
 * @property source 送り手(例 `crm`)
 * @property bodySha256 本文の SHA-256(小文字の 16 進)。同じ ID で本文が違う再送に気づくため
 * @property signedAt 送り手が署名した時刻(`webhook-timestamp`。再送のたびに新しくなる)
 * @property receivedAt 受け口が受け取った時刻
 */
public data class WebhookReceipt private constructor(
    val webhookId: String,
    val source: String,
    val bodySha256: String,
    val signedAt: Instant,
    val receivedAt: Instant,
) {
    /** この受け取りが、記録済みの [existing] の再送か。なければ新しい受け取り。 */
    public fun against(existing: WebhookReceipt?): ReceiptOutcome =
        when {
            existing == null -> ReceiptOutcome.Recorded
            else -> ReceiptOutcome.Duplicate(sameBody = existing.bodySha256 == bodySha256)
        }

    public companion object {
        private val WEBHOOK_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        private val SOURCE = Regex("^[a-z][a-z0-9-]{0,31}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        public fun of(
            webhookId: String,
            source: String,
            bodySha256: String,
            signedAt: Instant,
            receivedAt: Instant,
        ): Result<WebhookReceipt, ValidationError> {
            val violations =
                buildList {
                    if (!WEBHOOK_ID.matches(webhookId)) add(FieldViolation("webhookId", "英数字と _ . : - の 128 文字以内です"))
                    if (!SOURCE.matches(source)) add(FieldViolation("source", "英小文字で始まる英小文字・数字・- の 32 文字以内です"))
                    if (!SHA256.matches(bodySha256)) add(FieldViolation("bodySha256", "SHA-256 の小文字の 16 進(64 文字)です"))
                }
            return if (violations.isEmpty()) {
                ok(WebhookReceipt(webhookId, source, bodySha256, signedAt, receivedAt))
            } else {
                err(ValidationError(violations))
            }
        }
    }
}

/** 受け取りの結果。どちらも送り手には 204 を返す(同じ ID の再送は二度は取り込まない)。 */
public sealed interface ReceiptOutcome {
    /** 新しく記録し、取り込みのトピックへの Outbox に書いた。 */
    public data object Recorded : ReceiptOutcome

    /**
     * 記録済みの ID の再送。取り込まない。[sameBody] が false なら、同じ ID で本文が違う(送り手の誤り)。
     * 先に受け取った本文を正とし、メトリクスで数えて気づけるようにする。
     */
    public data class Duplicate(
        val sameBody: Boolean,
    ) : ReceiptOutcome
}

/**
 * 受け取りの記録の保持期間。送り手の再送の期間より長くする(短いと、記録を消した後の再送を新しい受け取りとして取り込む)。
 * 取り込みの側は版で新旧を判断するので、取り込み直しても結果は変わらないが、余分な処理を増やさない。
 */
public data class ReceiptRetention private constructor(
    public val value: Duration,
) {
    public companion object {
        /** Standard Webhooks の送り手の再送は数日で尽きる(saas-mock の既定は 3 時間余り)。 */
        public val MINIMUM: Duration = 7.days
        public val DEFAULT: ReceiptRetention = ReceiptRetention(30.days)

        public fun of(
            value: Duration,
            field: String = "retention",
        ): Result<ReceiptRetention, ValidationError> =
            if (value >= MINIMUM) {
                ok(ReceiptRetention(value))
            } else {
                err(ValidationError(listOf(FieldViolation(field, "$MINIMUM 以上にしてください(送り手の再送の期間より長くする。ADR-0035 §3)"))))
            }
    }
}
