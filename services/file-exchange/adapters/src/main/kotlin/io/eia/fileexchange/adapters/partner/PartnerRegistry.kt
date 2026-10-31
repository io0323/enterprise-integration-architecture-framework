package io.eia.fileexchange.adapters.partner

import io.eia.fileexchange.application.port.outbound.Partners
import io.eia.fileexchange.domain.PartnerId
import io.eia.platform.filetransfer.sftp.SftpConfig
import io.eia.platform.security.secret.SecretName
import io.eia.platform.tradingpartner.TradingPartnerLedger
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.map

/**
 * 売上ファイルの送り先(ADR-0032 §8)。取引先の台帳(platform/trading-partner。b2b-gateway と共有する 1 つの台帳。ADR-0040)のうち、
 * ファイルを送る取引先(扱う文書に dataset を持つ outbound の文書)から作る。送信は P12 ⑦ で b2b-gateway に移す。
 *
 * 台帳には秘密情報の**名前**だけを書く(値は [io.eia.platform.security.secret.SecretProvider] から読む。ADR-0019 §6)。
 * 自社の PGP の署名の鍵は取引先によらないので、台帳には書かない。
 *
 * @property ledger 送り先を作った台帳(鍵の期限のメトリクスに使う。台帳の設定がなければ null)
 */
public class PartnerRegistry(
    partners: List<PartnerDestination>,
    public val ledger: TradingPartnerLedger? = null,
) : Partners {
    private val byId: Map<PartnerId, PartnerDestination> = partners.associateBy { it.id }

    init {
        require(byId.size == partners.size) { "取引先の ID が重複しています" }
    }

    public val partners: List<PartnerDestination> get() = byId.values.sortedBy { it.id.value }

    public operator fun get(id: PartnerId): PartnerDestination? = byId[id]

    override fun recipients(dataset: String): List<PartnerId> = partners.filter { dataset in it.datasets }.map { it.id }

    public companion object {
        /** 台帳から、ファイルを送る取引先の送り先を作る(台帳の検証で、ファイルを送る取引先には送り先のディレクトリと PGP の公開鍵がある)。 */
        public fun of(ledger: TradingPartnerLedger): PartnerRegistry =
            PartnerRegistry(
                ledger.partners.mapNotNull { partner ->
                    val datasets = partner.documents.mapNotNull { it.dataset }.toSet()
                    if (datasets.isEmpty()) return@mapNotNull null
                    val sftp = partner.sftp
                    PartnerDestination(
                        id = PartnerId(partner.id),
                        datasets = datasets,
                        sftp = SftpConfig(sftp.host, sftp.port, sftp.username, sftp.privateKey, sftp.knownHosts, sftp.privateKeyPassphrase),
                        directory = checkNotNull(sftp.directories.outbound),
                        pgpPublicKey = checkNotNull(partner.pgp).publicKey,
                    )
                },
                ledger,
            )

        /** 台帳の JSON を読む。誤りは項目の場所と理由で返す(値は入れない。[TradingPartnerLedger.parse])。 */
        public fun parse(json: String): Result<PartnerRegistry, ValidationError> = TradingPartnerLedger.parse(json).map(::of)
    }
}

/**
 * ファイルの送り先(台帳の取引先のうち、ファイルを送るもの)。
 *
 * @property sftp 送り先の SFTP(ホスト鍵 `knownHosts`・クライアントの鍵は秘密情報の名前)
 * @property directory 送り先のディレクトリ(絶対パス)
 * @property pgpPublicKey 取引先の PGP の公開鍵(暗号化に使う)の秘密情報の名前
 */
public data class PartnerDestination(
    val id: PartnerId,
    val datasets: Set<String>,
    val sftp: SftpConfig,
    val directory: String,
    val pgpPublicKey: SecretName,
)
