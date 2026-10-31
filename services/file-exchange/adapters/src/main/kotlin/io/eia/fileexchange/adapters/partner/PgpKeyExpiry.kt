package io.eia.fileexchange.adapters.partner

import io.eia.platform.filetransfer.pgp.PgpKeys
import io.eia.platform.security.secret.SecretProvider
import io.eia.platform.tradingpartner.CredentialKind
import io.eia.platform.tradingpartner.EmbeddedExpiry
import io.eia.platform.tradingpartner.TradingPartner
import io.eia.shared.kernel.getOrNull
import java.time.Instant

/**
 * 取引先の PGP の公開鍵の中の有効期限(鍵の期限の監視。ADR-0040 §3)。鍵を読めないときは null(台帳の期日で監視する。
 * 読めない鍵は送信のときに鍵の誤りになる)。SSH の鍵とホスト鍵には期限がないので、台帳の期日だけで監視する。
 */
public class PgpKeyExpiry(
    private val secrets: SecretProvider,
) : EmbeddedExpiry {
    override fun of(
        partner: TradingPartner,
        kind: CredentialKind,
    ): Instant? =
        partner.pgp
            ?.takeIf { kind == CredentialKind.PGP_PUBLIC_KEY }
            ?.let { secrets.get(it.publicKey).getOrNull() }
            ?.let(PgpKeys::publicKeyExpiry)
}
