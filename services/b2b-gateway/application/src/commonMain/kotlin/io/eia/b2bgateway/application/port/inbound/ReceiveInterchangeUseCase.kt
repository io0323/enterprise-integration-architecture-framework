package io.eia.b2bgateway.application.port.inbound

import io.eia.b2bgateway.domain.agreement.PartnerAgreement
import io.eia.b2bgateway.domain.order.BusinessRule
import io.eia.b2bgateway.domain.order.CodeResolution
import io.eia.b2bgateway.domain.order.PurchaseOrder
import io.eia.b2bgateway.domain.receipt.DuplicateCheck
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.Result

/**
 * 取引先から受け取った交換(原本のバイト列)を、構文 → スキーマ → 業務の 3 段階で検査し、応答(CONTRL と、拒否の APERAK)を組み立てる
 * (ADR-0041 §4・§5・§11)。原本の保管・受け取りの記録・監査・応答の送信は、このユースケースの結果を使って adapters が行う(P12 ④)。
 *
 * - 交換の送り手が取引先の台帳の識別子と違えば 0085 = 23、受け手が自社でなければ 7 で交換を拒否する。
 * - 同じ管理番号の送り直し(同じ SHA-256)は取り込まず、前に返した応答を返し直す。中身が違えば 0085 = 26 で拒否する。
 * - 業務の段階で拒否した注文には APERAK(RE)を返す。受け付けた注文の肯定の APERAK(AP)は、社内に取り込んだ後に返す(P12 ⑤)。
 *   試験の交換は社内に取り込まないので、取引先が肯定の応答を選んでいれば、ここで返す(ADR-0041 §5・§6)。
 *
 * @return 結果。取引先の ID が台帳になければ [UnknownPartner](設定の誤り)。Port のエラーはそのまま返す(呼び出し元がやり直す)
 */
public interface ReceiveInterchangeUseCase {
    public suspend operator fun invoke(document: ReceivedDocument): Result<Receipt, DomainError>
}

/**
 * 受け取った交換。[partnerId] はファイルを受け取った SFTP の場所の取引先、[sha256] は原本の SHA-256(小文字の 16 進数。adapters が
 * 原本を保管するときに求める)。
 */
public class ReceivedDocument(
    public val partnerId: String,
    public val content: ByteArray,
    public val sha256: String,
)

/**
 * 検査の結果。[result] は台帳の照合と重複の判定を含めた最終の結果。[orders] は業務の段階まで進んだ注文([OrderDecision.accepted] が
 * true なら社内に取り込む)。[contrl]・[aperak] は取引先に返す応答(返すものがなければ null)。
 */
public class Receipt(
    public val partner: PartnerAgreement,
    public val result: InterchangeResult,
    public val duplicate: DuplicateCheck,
    public val orders: List<OrderDecision>,
    public val contrl: ByteArray?,
    public val aperak: ByteArray?,
)

/** 1 つの注文(メッセージ)の業務の段階の結果。[resolution] は社内への取り込みで使う、対応表を引いた結果。 */
public data class OrderDecision(
    val messageReference: String,
    val order: PurchaseOrder,
    val violations: List<BusinessRule>,
    val resolution: CodeResolution,
) {
    val accepted: Boolean get() = violations.isEmpty()
}

/** 取引先の ID が台帳にない(ファイルの置き場所と台帳の設定の食い違い。取引先の誤りではないので、応答を返さない)。 */
public data class UnknownPartner(
    val partnerId: String,
) : DomainError.NonRetryable {
    override val code: String get() = "unknown_partner"
    override val message: String get() = "取引先 $partnerId が台帳にありません"
}
