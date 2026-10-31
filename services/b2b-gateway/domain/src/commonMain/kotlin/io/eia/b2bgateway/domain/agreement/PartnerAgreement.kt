package io.eia.b2bgateway.domain.agreement

import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.shared.kernel.money.Currency
import kotlin.time.Duration

/**
 * 取引先との合意(TPA)のうち、受注の文書の検査と応答に使う項目(Framework 11.1)。台帳(P12 ③)から adapters が作る。
 *
 * @property partnerId 取引先の ID(台帳のキー。ファイルを受け取った SFTP の場所で決まる)
 * @property sender 取引先の UNB の送り手(S002 の識別子と識別の機関)。届いた交換の送り手が違えば 0085 = 23
 * @property recipient 自社の UNB の受け手(S003)。届いた交換の受け手が違えば 0085 = 7
 * @property ownGln 自社の GLN(注文の NAD+SU。違えば ORD003)
 * @property currencies 合意した通貨(注文の CUX。ほかの通貨は ORD005)
 * @property positiveAperak 業務の受け付けに成功したときにも APERAK(AP)を返すか(P12 の承認の条件 1)
 * @property aperakDeadline 受け取ってから業務の応答(APERAK)を返すまでの期限(承認の条件 1。期限に近づいた送信待ちはアラートにする)
 */
public data class PartnerAgreement(
    public val partnerId: String,
    public val sender: PartyIdentification,
    public val recipient: PartyIdentification,
    public val ownGln: String,
    public val currencies: Set<Currency>,
    public val positiveAperak: Boolean,
    public val aperakDeadline: Duration,
)
