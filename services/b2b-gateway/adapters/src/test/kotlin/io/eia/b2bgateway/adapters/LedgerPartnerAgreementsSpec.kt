package io.eia.b2bgateway.adapters

import io.eia.b2bgateway.adapters.partner.LedgerPartnerAgreements
import io.eia.b2bgateway.domain.agreement.PartnerAgreement
import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.platform.testsupport.TestLedgers
import io.eia.platform.tradingpartner.TradingPartnerLedger
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.ValidationError
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours

private fun ledger(vararg partners: TestLedgers.Partner): TradingPartnerLedger =
    TradingPartnerLedger.parse(TestLedgers.json(*partners)).shouldBeInstanceOf<Result.Ok<TradingPartnerLedger>>().value

class LedgerPartnerAgreementsSpec :
    FunSpec({
        test("受注の文書を受け取る取引先の合意を、台帳の識別子・自社の識別子・合意から作る") {
            val agreements =
                LedgerPartnerAgreements
                    .of(ledger(TestLedgers.Partner(positiveAperak = false), TestLedgers.Partner(id = "files-only", edi = false)))
                    .shouldBeInstanceOf<Result.Ok<LedgerPartnerAgreements>>()
                    .value

            agreements.find("partner01") shouldBe
                ok(
                    PartnerAgreement(
                        partnerId = "partner01",
                        sender = PartyIdentification("4912345000019", "14"),
                        recipient = PartyIdentification("4900000000001", "14"),
                        ownGln = "4900000000001",
                        currencies = setOf(Currency.JPY),
                        positiveAperak = false,
                        aperakDeadline = 4.hours,
                    ),
                )
            agreements.find("files-only") shouldBe ok(null)
            agreements.find("nobody") shouldBe ok(null)
        }

        test("合意した通貨が知らない通貨なら設定の誤り") {
            LedgerPartnerAgreements
                .of(
                    TradingPartnerLedger
                        .parse(
                            TestLedgers.json(TestLedgers.Partner()).replace("[\"JPY\"]", "[\"XTS\"]"),
                        ).shouldBeInstanceOf<Result.Ok<TradingPartnerLedger>>()
                        .value,
                ).shouldBeInstanceOf<Result.Err<ValidationError>>()
                .error.violations
                .single()
                .field shouldBe "partners.partner01.agreement.currencies"
        }
    })
