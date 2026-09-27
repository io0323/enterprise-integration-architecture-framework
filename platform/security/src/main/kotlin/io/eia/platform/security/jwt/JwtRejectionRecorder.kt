package io.eia.platform.security.jwt

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import org.slf4j.LoggerFactory

/**
 * 拒否したトークンを、理由のコードだけでログとメトリクスに残す(ADR-0019 §5・§7)。トークンやクレームの値は受け取らない。
 *
 * @param meter null なら数えない
 */
internal class JwtRejectionRecorder(
    meter: Meter?,
) {
    private val rejections: LongCounter? =
        meter
            ?.counterBuilder(METRIC)
            ?.setDescription("拒否したアクセストークンの件数(理由別)")
            ?.setUnit("{token}")
            ?.build()

    fun record(error: JwtVerificationError) {
        val reason = (error as? JwtVerificationError.InvalidToken)?.reason?.code ?: error.code
        rejections?.add(1, Attributes.of(REASON, reason))
        if (error is JwtVerificationError.KeysUnavailable) {
            logger.warn("JWKS を取得できないため、アクセストークンを検証できません")
        } else {
            logger.debug("アクセストークンを拒否しました reason={}", reason)
        }
    }

    companion object {
        const val METRIC: String = "eia.security.jwt.rejections"
        private val REASON: AttributeKey<String> = AttributeKey.stringKey("reason")

        // ログの出力元は JwtVerifier のまま(運用で見るロガー名を変えない)
        private val logger = LoggerFactory.getLogger(JwtVerifier::class.java)
    }
}
