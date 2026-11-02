package io.eia.b2bgateway.application.port.inbound

import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 受け付けた注文を、社内の受注のシステム(order の API)に送る(ADR-0040 §6・ADR-0041 §5)。送信待ちを 1 件ずつ処理する。
 * - order が受け付けた: 取引先が肯定の応答を選んでいれば(TPA)、APERAK(AP)を作って送信待ちにする
 * - order が業務の誤りとして受け付けなかった: APERAK(RE。理由は固定の文言 APR901 だけ)を作って送信待ちにする
 * - 一時的な失敗・設定の誤り: 送信待ちのまま残し、次の回に同じ本文と Idempotency-Key で送り直す
 * - 送り直しの上限を過ぎた: 送らずに期限切れにする(order の Idempotency-Key の保存期間を超えて送り直さない。運用者が手で対応する)
 *
 * 1 件の失敗は、ほかの注文の送信を止めない。
 *
 * @return 送信の結果。送信待ちの一覧を読めなければ、そのエラー
 */
public interface SubmitOrdersUseCase {
    public suspend operator fun invoke(): Result<SubmitSummary, DomainError>
}

/** 送信の結果(送信待ちの ID ごと)。 */
public class SubmitSummary(
    public val accepted: List<String>,
    public val refused: List<String>,
    public val expired: List<String>,
    public val failed: List<FailedSubmission>,
)

public data class FailedSubmission(
    val id: String,
    val error: DomainError,
)
