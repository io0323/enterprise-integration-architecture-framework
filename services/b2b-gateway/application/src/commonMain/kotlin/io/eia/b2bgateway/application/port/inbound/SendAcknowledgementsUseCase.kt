package io.eia.b2bgateway.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 取引先の送信待ちの応答(CONTRL・APERAK)を送る(ADR-0040 §7・ADR-0041 §5)。1 件ずつ、保管した原本(暗号文)を読み、SHA-256 を記録と
 * 照らし合わせてから、送信の仕組み([PartnerOutbox][io.eia.b2bgateway.application.port.outbound.PartnerOutbox])で送り、
 * 送った印と監査を記録する。1 件の失敗は、ほかの応答の送信を止めない(失敗した応答は送信待ちのまま残し、次の回に送る)。
 *
 * @return 送信の結果。送信待ちの一覧を読めなければ、そのエラー
 */
public interface SendAcknowledgementsUseCase {
    public suspend operator fun invoke(partnerId: String): Result<SendSummary, DomainError>
}

/** 送信の結果。[sent] は送った(または取引先の側にすでにあった)応答の ID。 */
public class SendSummary(
    public val sent: List<String>,
    public val failed: List<FailedAcknowledgement>,
)

public data class FailedAcknowledgement(
    val id: String,
    val error: DomainError,
)

/** 保管した原本の SHA-256 が、記録と一致しない(原本の置き場の誤り。送らずに運用に知らせる)。 */
public data class OriginalMismatch(
    val ref: String,
) : DomainError.NonRetryable {
    override val code: String get() = "original_mismatch"
    override val message: String get() = "保管した原本の SHA-256 が記録と一致しません($ref)"
}
