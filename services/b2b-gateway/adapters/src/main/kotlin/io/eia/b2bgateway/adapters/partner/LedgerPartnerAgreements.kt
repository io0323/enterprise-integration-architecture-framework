package io.eia.b2bgateway.adapters.partner

import io.eia.b2bgateway.application.port.outbound.PartnerAgreements
import io.eia.b2bgateway.domain.agreement.PartnerAgreement
import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.platform.tradingpartner.Direction
import io.eia.platform.tradingpartner.TradingPartnerLedger
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FieldViolation
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.err
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.ok
import kotlin.time.toKotlinDuration

/**
 * 取引先との合意(TPA)を、取引先の台帳(platform/trading-partner。ADR-0040)から作る。受注の文書(INT-B2B-001)を受け取る取引先だけを持つ。
 * 台帳は起動時に読む(差し替えは再起動。台帳の変更は、鍵の差し替えと同じく運用の手順で行う)。
 */
public class LedgerPartnerAgreements private constructor(
    private val agreements: Map<String, PartnerAgreement>,
) : PartnerAgreements {
    override suspend fun find(partnerId: String): Result<PartnerAgreement?, DomainError> = ok(agreements[partnerId])

    public companion object {
        /** 受注の文書の連携(contracts/catalog/INT-B2B-001.yaml) */
        public const val ORDERS_INTEGRATION: String = "INT-B2B-001"

        /** 台帳から作る。合意した通貨が知らない通貨なら設定の誤り(小数桁数がわからないと、単価の検査ができない)。 */
        public fun of(ledger: TradingPartnerLedger): Result<LedgerPartnerAgreements, ValidationError> {
            val violations = mutableListOf<FieldViolation>()
            val company = ledger.company
            val agreements =
                ledger.partnersFor(ORDERS_INTEGRATION, Direction.INBOUND).associate { partner ->
                    val currencies =
                        partner.agreement.currencies.mapNotNull { code ->
                            Currency.COMMON.firstOrNull { it.code == code }.also {
                                if (it == null) {
                                    violations += FieldViolation("partners.${partner.id}.agreement.currencies", "知らない通貨 $code です")
                                }
                            }
                        }
                    val sender = checkNotNull(partner.edifact) { "台帳の検証で、文書を受け取る取引先には送り手の識別子があります" }
                    partner.id to
                        PartnerAgreement(
                            partnerId = partner.id,
                            sender = PartyIdentification(sender.id, sender.qualifier),
                            recipient = PartyIdentification(company.edifact.id, company.edifact.qualifier),
                            ownGln = company.gln,
                            currencies = currencies.toSet(),
                            positiveAperak = partner.agreement.positiveAperak,
                            aperakDeadline = partner.agreement.aperakDeadline.toKotlinDuration(),
                        )
                }
            return if (violations.isEmpty()) ok(LedgerPartnerAgreements(agreements)) else err(ValidationError(violations))
        }
    }
}
