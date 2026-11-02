package io.eia.b2bgateway.application.port.outbound

import io.eia.b2bgateway.domain.order.OrderRequest
import io.eia.b2bgateway.domain.validation.InterchangeHeader
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result
import kotlin.time.Instant

/**
 * 受け付けた注文を社内に取り込むための送信待ち(ADR-0040 §6)。受け付けの時点で、受け取りの記録と同じトランザクションで書く
 * ([DocumentRecord.submissions])。要求の本文は、このときに固定する(送り直しでも同じバイト列を送り、order の Idempotency-Key の
 * 本文の照合を通す)。
 *
 * @property header 受け取った交換の UNB(業務の応答 APERAK を、受け取った交換の区切り文字・文字セット・宛先で組み立てるため)
 * @property positiveAperak 受け付けの時点の TPA の「肯定の APERAK を返すか」
 * @property aperakDeadline 業務の応答を返す期限(受け取った時刻 + TPA の `aperakDeadline`。近づいたらアラート)
 * @property retryUntil 送り直しの上限(order の Idempotency-Key の保存期間 24 時間より短くする。過ぎたら手動の対応)
 */
@Suppress("LongParameterList") // 送信待ちの 1 行の項目(受け付けの時点で決まる値をまとめて渡す)
public class OrderSubmission(
    public val orderNumber: String,
    public val messageReference: String,
    public val request: OrderRequest,
    public val header: InterchangeHeader,
    public val positiveAperak: Boolean,
    public val aperakDeadline: Instant,
    public val retryUntil: Instant,
)

/** 送信待ちの注文。[body] は受け付けの時点で固定した要求の本文(契約の JSON)。 */
@Suppress("LongParameterList") // 送信待ちの 1 行の項目
public class PendingSubmission(
    public val id: String,
    public val documentId: String,
    public val partnerId: String,
    public val orderNumber: String,
    public val idempotencyKey: String,
    public val body: ByteArray,
    public val header: InterchangeHeader,
    public val positiveAperak: Boolean,
    public val retryUntil: Instant,
)

/** 送信待ちの注文の置き場。結果の記録(業務の応答の送信待ちを含む)と監査(`b2b.order.submit`)は、それぞれ 1 つのトランザクションで書く。 */
public interface OrderSubmissions {
    /** 送信待ち(古い順に [limit] 件まで)。 */
    public suspend fun pending(limit: Int): Result<List<PendingSubmission>, DomainError>

    /** order が受け付けた。[acknowledgement] は肯定の APERAK(返さない取引先なら null)。 */
    public suspend fun accepted(
        submission: PendingSubmission,
        orderId: String,
        acknowledgement: StoredAcknowledgement?,
    ): Result<Unit, DomainError>

    /** order が業務の誤りとして受け付けなかった([status] は応答のステータス)。[acknowledgement] は拒否の APERAK(APR901)。 */
    public suspend fun refused(
        submission: PendingSubmission,
        status: Int,
        acknowledgement: StoredAcknowledgement,
    ): Result<Unit, DomainError>

    /** 送り直しの上限を過ぎた(送らずに手動の対応にする)。 */
    public suspend fun expired(submission: PendingSubmission): Result<Unit, DomainError>

    /** 送れなかった(一時的な失敗・設定の誤り)。送信待ちのまま、試みた回数と失敗のコードを残す。 */
    public suspend fun failed(
        submission: PendingSubmission,
        errorCode: String,
    ): Result<Unit, DomainError>
}

/** 社内の受注のシステム(order の API。Gateway の `/sales/v1/orders`。INT-SALES-001)。 */
public interface OrderApi {
    /**
     * 固定した本文を、[idempotencyKey] を付けて送る。受け付けた・業務の誤りで受け付けなかった、は [Placement]。
     * 一時的な失敗(接続・タイムアウト・429・5xx・処理中の 409)と設定の誤り(認可・キーの使い回し)は Result のエラー(送信待ちのまま残す)。
     */
    public suspend fun place(
        idempotencyKey: String,
        body: ByteArray,
    ): Result<Placement, DomainError>
}

public sealed interface Placement {
    /** 登録された(送り直しで、前の応答が返ったときも)。 */
    public data class Accepted(
        val orderId: String,
    ) : Placement

    /** 業務の誤りとして受け付けなかった(400・422)。理由は取引先に返さない(ADR-0041 §5) */
    public data class Refused(
        val status: Int,
    ) : Placement
}
