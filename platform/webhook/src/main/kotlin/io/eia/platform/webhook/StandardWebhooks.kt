package io.eia.platform.webhook

import io.eia.platform.security.secret.Secret
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.err
import io.eia.shared.kernel.ok
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Standard Webhooks の形の署名(ADR-0035 §2。INTEGRATION_STANDARDS §2)。
 *
 * - ヘッダ: [ID](イベント ID。再送でも同じ)・[TIMESTAMP](署名した時刻。UNIX 秒)・[SIGNATURE](`v1,<base64>` を空白区切りで 1 つ以上)。
 * - 署名: `{id}.{timestamp}.{本文のバイト列}` の HMAC-SHA256。鍵は `whsec_` に続く base64(Standard Webhooks の形)か、それ以外は文字列の UTF-8。
 * - 送り手は再送のたびに新しい時刻で署名し直す(受け口は時刻の幅でリプレイを拒否するため)。
 */
public object StandardWebhooks {
    public const val ID: String = "webhook-id"
    public const val TIMESTAMP: String = "webhook-timestamp"
    public const val SIGNATURE: String = "webhook-signature"
    public const val VERSION: String = "v1"
    private const val ALGORITHM = "HmacSHA256"
    private const val SECRET_PREFIX = "whsec_"
    private const val SEPARATOR = '.'

    /**
     * [SIGNATURE] のヘッダの値。[secrets] のそれぞれで署名し、空白でつなぐ(鍵の差し替えの間は新旧の両方で署名する)。
     */
    public fun signatureHeader(
        secrets: List<Secret>,
        id: String,
        timestamp: Instant,
        body: ByteArray,
    ): String {
        require(secrets.isNotEmpty()) { "鍵が要ります" }
        return secrets.joinToString(" ") { "$VERSION,${Base64.getEncoder().encodeToString(sign(it, id, timestamp.epochSeconds, body))}" }
    }

    internal fun sign(
        secret: Secret,
        id: String,
        timestamp: Long,
        body: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(keyBytes(secret), ALGORITHM))
        mac.update("$id$SEPARATOR$timestamp$SEPARATOR".toByteArray(Charsets.UTF_8))
        return mac.doFinal(body)
    }

    private fun keyBytes(secret: Secret): ByteArray {
        val value = secret.reveal()
        return if (value.startsWith(SECRET_PREFIX)) {
            Base64.getDecoder().decode(value.removePrefix(SECRET_PREFIX))
        } else {
            value.toByteArray(Charsets.UTF_8)
        }
    }

    /** 比較は定数時間(一致した長さで時間が変わらない)。 */
    internal fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = MessageDigest.isEqual(a, b)
}

/** 受け取った署名のヘッダの値(欠けていれば null)。 */
public data class WebhookHeaders(
    val id: String?,
    val timestamp: String?,
    val signature: String?,
)

/** 署名を検証した Webhook。[matchedSecret] は一致した鍵の位置(0 が今の鍵、1 が差し替え前の鍵)。メトリクスで差し替えの進み具合を見る。 */
public data class VerifiedWebhook(
    val id: String,
    val signedAt: Instant,
    val matchedSecret: Int,
)

/**
 * 受け口の署名の検証(ADR-0035 §2)。**本文を解析する前に、受け取った生のバイト列で** 検証する。
 *
 * @param tolerance 署名の時刻と受け口の時刻の差の許容(前後)。超えたらリプレイとして拒否する。既定は 5 分
 */
public class WebhookVerifier(
    private val tolerance: Duration = DEFAULT_TOLERANCE,
) {
    /**
     * @param secrets 受け付ける鍵(今の鍵と、差し替えの間は差し替え前の鍵)。どれかで署名が一致すれば受け付ける
     * @param now 受け口の時刻
     */
    @Suppress("ReturnCount") // ヘッダの欠落・形式・時刻・署名のそれぞれで返す
    public fun verify(
        headers: WebhookHeaders,
        body: ByteArray,
        secrets: List<Secret>,
        now: Instant,
    ): Result<VerifiedWebhook, WebhookRejection> {
        val (id, timestamp, signature) = headers
        if (id.isNullOrBlank()) return err(WebhookRejection.MissingHeader(StandardWebhooks.ID))
        if (timestamp.isNullOrBlank()) return err(WebhookRejection.MissingHeader(StandardWebhooks.TIMESTAMP))
        if (signature.isNullOrBlank()) return err(WebhookRejection.MissingHeader(StandardWebhooks.SIGNATURE))
        if (!ID_PATTERN.matches(id)) return err(WebhookRejection.MalformedHeader(StandardWebhooks.ID))
        val seconds =
            timestamp.takeIf { TIMESTAMP_PATTERN.matches(it) }?.toLongOrNull()
                ?: return err(WebhookRejection.MalformedHeader(StandardWebhooks.TIMESTAMP))
        val signedAt = Instant.fromEpochSeconds(seconds)
        if ((now - signedAt).absoluteValue > tolerance) return err(WebhookRejection.TimestampOutOfRange)
        if (secrets.isEmpty()) return err(WebhookRejection.NoSecret)
        val provided =
            signature
                .split(' ')
                .filter { it.startsWith("${StandardWebhooks.VERSION},") }
                .mapNotNull { decode(it.substringAfter(',')) }
        if (provided.isEmpty()) return err(WebhookRejection.MalformedHeader(StandardWebhooks.SIGNATURE))
        val matched =
            secrets.indexOfFirst { secret ->
                val expected = StandardWebhooks.sign(secret, id, seconds, body)
                provided.any { StandardWebhooks.constantTimeEquals(expected, it) }
            }
        return if (matched >= 0) ok(VerifiedWebhook(id, signedAt, matched)) else err(WebhookRejection.SignatureMismatch)
    }

    private fun decode(value: String): ByteArray? =
        try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    public companion object {
        public val DEFAULT_TOLERANCE: Duration = 5.minutes
        private val ID_PATTERN = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        private val TIMESTAMP_PATTERN = Regex("^[0-9]{1,12}$")
    }
}

/**
 * 署名の検証の失敗。どれも記録せずに拒否する(401。ヘッダの欠落・形式の誤りは 400)。[code] はメトリクスの属性に使う(値は入れない)。
 * 鍵が読めないとき([NoSecret])だけは受け口の問題なので、送り手に再送させる(503)。
 */
public sealed interface WebhookRejection {
    public val code: String

    public data class MissingHeader(
        val header: String,
    ) : WebhookRejection,
        DomainError.NonRetryable {
        override val code: String get() = "webhook_header_missing"
        override val message: String get() = "$header がありません"
    }

    public data class MalformedHeader(
        val header: String,
    ) : WebhookRejection,
        DomainError.NonRetryable {
        override val code: String get() = "webhook_header_malformed"
        override val message: String get() = "$header の形式が誤っています"
    }

    /** 署名の時刻が許容の幅を外れた(リプレイ)。 */
    public data object TimestampOutOfRange : WebhookRejection, DomainError.NonRetryable {
        override val code: String get() = "webhook_timestamp_out_of_range"
        override val message: String get() = "署名の時刻が許容の幅を外れています"
    }

    public data object SignatureMismatch : WebhookRejection, DomainError.NonRetryable {
        override val code: String get() = "webhook_signature_invalid"
        override val message: String get() = "署名が一致しません"
    }

    /** 受け付ける鍵がない(鍵を読めない)。 */
    public data object NoSecret : WebhookRejection, DomainError.Retryable {
        override val code: String get() = "webhook_secret_unavailable"
        override val message: String get() = "署名の鍵がありません"
    }
}
