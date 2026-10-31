package io.eia.platform.tradingpartner

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableDoubleGauge
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 期限を監視する鍵の種類(Framework 11.3 のアンチパターン「証明書の期限切れの放置」。ADR-0040 §3)。 */
public enum class CredentialKind(
    public val label: String,
) {
    SFTP_HOST_KEY("sftp_host_key"),
    SFTP_CLIENT_KEY("sftp_client_key"),
    PGP_PUBLIC_KEY("pgp_public_key"),
}

/** 鍵 1 つの有効期限。[expiresAt] を過ぎたら使えない(台帳の validUntil は、その日の終わり(UTC)まで有効とする)。 */
public data class CredentialExpiry(
    val partnerId: String,
    val kind: CredentialKind,
    val expiresAt: Instant,
)

/**
 * 鍵の中に書かれた期限を読む(PGP の公開鍵。読めない・期限がなければ null)。鍵を読むのは platform/file-transfer なので、呼び出し側が渡す。
 */
public fun interface EmbeddedExpiry {
    public fun of(
        partner: TradingPartner,
        kind: CredentialKind,
    ): Instant?
}

public object CredentialExpiries {
    /** 台帳の全取引先の鍵の有効期限。鍵の中の期限([embedded])が台帳の期日より早ければ、そちらを使う。 */
    public fun of(
        ledger: TradingPartnerLedger,
        embedded: EmbeddedExpiry = EmbeddedExpiry { _, _ -> null },
    ): List<CredentialExpiry> =
        ledger.partners.flatMap { partner ->
            listOfNotNull(
                CredentialKind.SFTP_HOST_KEY to partner.sftp.hostKeyValidUntil,
                CredentialKind.SFTP_CLIENT_KEY to partner.sftp.clientKeyValidUntil,
                partner.pgp?.let { CredentialKind.PGP_PUBLIC_KEY to it.validUntil },
            ).map { (kind, validUntil) ->
                val agreed = endOfDay(validUntil)
                val inKey = embedded.of(partner, kind)
                CredentialExpiry(partner.id, kind, if (inKey != null && inKey < agreed) inKey else agreed)
            }
        }

    private fun endOfDay(date: LocalDate): Instant = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
}

/**
 * 鍵の有効期限のメトリクス(ADR-0040 §3)。`eia.trading_partner.credential.expiry`(期限の時刻。UNIX 秒)を、取引先と鍵の種類ごとに出す。
 * アラートは `prometheus/rules/trading-partner.rules.yml`(残り 30 日で警告、7 日で重大、過ぎたら重大)。手順は docs/runbooks/b2b-partner-credentials.md。
 * 値は読み出しのたびに [expiries] から求める(鍵の差し替えに追従する)。
 */
public class CredentialExpiryMetrics(
    meter: Meter,
    private val expiries: () -> List<CredentialExpiry>,
) : AutoCloseable {
    private val gauge: ObservableDoubleGauge =
        meter
            .gaugeBuilder(NAME)
            .setUnit("s")
            .setDescription("取引先の鍵とホスト鍵の有効期限(UNIX 秒)")
            .buildWithCallback { measurement ->
                expiries().forEach {
                    measurement.record(
                        it.expiresAt.epochSecond.toDouble(),
                        Attributes.of(PARTNER, it.partnerId, CREDENTIAL, it.kind.label),
                    )
                }
            }

    override fun close() {
        gauge.close()
    }

    public companion object {
        public const val NAME: String = "eia.trading_partner.credential.expiry"
        public val PARTNER: AttributeKey<String> = AttributeKey.stringKey("trading_partner.id")
        public val CREDENTIAL: AttributeKey<String> = AttributeKey.stringKey("trading_partner.credential")
    }
}
