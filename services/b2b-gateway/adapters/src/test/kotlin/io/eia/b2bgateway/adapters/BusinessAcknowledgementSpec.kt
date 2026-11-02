package io.eia.b2bgateway.adapters

import io.eia.b2bgateway.adapters.spec.YamlEdiSpecs
import io.eia.b2bgateway.application.port.inbound.Receipt
import io.eia.b2bgateway.application.port.inbound.ReceivedDocument
import io.eia.b2bgateway.application.port.outbound.CodeLookup
import io.eia.b2bgateway.application.port.outbound.CodeMappings
import io.eia.b2bgateway.application.port.outbound.ControlReferences
import io.eia.b2bgateway.application.port.outbound.PartnerAgreements
import io.eia.b2bgateway.application.port.outbound.ReceivedInterchanges
import io.eia.b2bgateway.application.port.outbound.ReceivedOrders
import io.eia.b2bgateway.application.usecase.ReceiveInterchangeService
import io.eia.b2bgateway.application.usecase.ReceivingPorts
import io.eia.b2bgateway.domain.agreement.PartnerAgreement
import io.eia.b2bgateway.domain.edifact.EdifactLexer
import io.eia.b2bgateway.domain.edifact.Latin1
import io.eia.b2bgateway.domain.order.BusinessRule
import io.eia.b2bgateway.domain.order.CodeResolution
import io.eia.b2bgateway.domain.order.InternalCustomer
import io.eia.b2bgateway.domain.order.InternalItem
import io.eia.b2bgateway.domain.order.ItemCode
import io.eia.b2bgateway.domain.order.ShippingAddress
import io.eia.b2bgateway.domain.receipt.InterchangeKey
import io.eia.b2bgateway.domain.receipt.PreviousReceipt
import io.eia.b2bgateway.domain.spec.EdiSpec
import io.eia.b2bgateway.domain.validation.InterchangeCheck
import io.eia.b2bgateway.domain.validation.InterchangeResult
import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.shared.kernel.DomainError
import io.eia.shared.kernel.FixedClock
import io.eia.shared.kernel.Result
import io.eia.shared.kernel.money.Currency
import io.eia.shared.kernel.ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val specs = YamlEdiSpecs()

private val AGREEMENT =
    PartnerAgreement(
        partnerId = "partner01",
        sender = PartyIdentification("4912345000019", "14"),
        recipient = PartyIdentification("4900000000001", "14"),
        ownGln = "4900000000001",
        currencies = setOf(Currency.JPY),
        positiveAperak = true,
        aperakDeadline = 4.hours,
    )

/** 取引先の台帳・対応表・記録の代わり。対応表は契約のサンプル(EdiSpecsSpec の正しい注文)のコードだけを持つ。 */
private object Ports : PartnerAgreements, ReceivedInterchanges, CodeMappings, ReceivedOrders, ControlReferences {
    private var reference = 0

    override suspend fun find(partnerId: String): Result<PartnerAgreement?, DomainError> = ok(AGREEMENT)

    override suspend fun find(key: InterchangeKey): Result<PreviousReceipt?, DomainError> = ok(null)

    override suspend fun resolve(
        partnerId: String,
        lookup: CodeLookup,
    ): Result<CodeResolution, DomainError> =
        ok(
            CodeResolution(
                buyers =
                    mapOf("4912345000019" to InternalCustomer("cust-1", ShippingAddress("JP", "100-0001", null, "Chiyoda", "1-1", null))),
                deliveryParties = mapOf("4912345000026" to ShippingAddress("JP", "100-0001", null, "Chiyoda", "1-1", null)),
                items =
                    mapOf(
                        ItemCode.BuyerPartNumber("ABC-123") to InternalItem("prod-1", "SKU-1"),
                        ItemCode.Gtin("4901234567900") to InternalItem("prod-2", "SKU-2"),
                    ),
            ),
        )

    override suspend fun exists(
        partnerId: String,
        orderNumber: String,
    ): Result<Boolean, DomainError> = ok(false)

    override suspend fun next(recipient: PartyIdentification): Result<String, DomainError> = ok("R-${++reference}")
}

private val service =
    ReceiveInterchangeService(specs, ReceivingPorts(Ports, Ports, Ports, Ports, Ports), FixedClock(Instant.parse("2026-10-31T00:00:00Z")))

/** 契約の仕様のサンプルの注文(明細 2 つ)。[price] で 2 つ目の明細の単価を変える。 */
private fun orders(
    price: String,
    test: Boolean = false,
): String {
    val body =
        listOf(
            "BGM+220+PO-1001+9",
            "DTM+137:20261030:102",
            "DTM+2:20261105:102",
            "NAD+BY+4912345000019::9",
            "NAD+SU+4900000000001::9",
            "NAD+DP+4912345000026::9",
            "CUX+2:JPY:9",
            "LIN+1++4901234567894:EN",
            "PIA+1+ABC-123:BP",
            "QTY+21:10",
            "PRI+AAA:1200",
            "LIN+2++4901234567900:EN",
            "QTY+21:5",
            "PRI+AAA:$price",
            "UNS+S",
            "CNT+2:2",
        )
    val unb = "UNB+UNOA:3+4912345000019:14+4900000000001:14+261030:1200+ICR0001" + if (test) "++++++1" else ""
    return "UNA:+.? '" +
        (listOf(unb, "UNH+1+ORDERS:D:96A:UN") + body + listOf("UNT+${body.size + 2}+1", "UNZ+1+ICR0001")).joinToString("'", postfix = "'")
}

private suspend fun receive(text: String): Receipt =
    service(ReceivedDocument("partner01", Latin1.encode(text), "sha")).shouldBeInstanceOf<Result.Ok<Receipt>>().value

/** 応答を契約の仕様で検査する(自分の出す応答が、自分の仕様に従うこと)。 */
private fun shouldConformTo(
    spec: EdiSpec,
    bytes: ByteArray?,
) {
    val result =
        InterchangeCheck
            .check(
                EdifactLexer.read(Latin1.decode(checkNotNull(bytes))),
                spec,
            ).shouldBeInstanceOf<InterchangeResult.Checked>()
    result.messages.forEach {
        it.header shouldBe null
        it.errors.shouldBeEmpty()
    }
}

class BusinessAcknowledgementSpec :
    FunSpec({
        test("業務の規則のコード(BusinessRule)は、受注の文書の仕様の businessRules と一致する") {
            BusinessRule.entries.map { it.code } shouldContainExactly specs.orders.businessRules.map { it.code }
        }

        test("業務の段階で拒否した注文(JPY の単価に小数)には APERAK(RE)を返し、APERAK は仕様に従い、仕様のコードと文言だけを含む") {
            val receipt = receive(orders(price = "980.5"))

            receipt.orders.single().violations shouldContainExactly listOf(BusinessRule.INVALID_PRICE)
            shouldConformTo(specs.contrl, receipt.contrl)
            shouldConformTo(specs.aperak, receipt.aperak)
            val segments = EdifactLexer.read(Latin1.decode(checkNotNull(receipt.aperak))).segments
            segments.filter { it.tag == "ERC" }.map { it.value(1, 1) } shouldContainExactly listOf("ORD011")
            segments.filter { it.tag == "FTX" }.map { it.value(4) } shouldContainExactly listOf("INVALID PRICE")
        }

        test("正しい注文は受け付け、APERAK は返さない(肯定の応答は社内に取り込んだ後。P12 ⑤)") {
            val receipt = receive(orders(price = "980"))

            receipt.orders.single().accepted shouldBe true
            receipt.aperak shouldBe null
        }

        test("試験の交換で取引先が肯定の応答を選んでいれば、APERAK(AP)を返し、仕様に従う") {
            val receipt = receive(orders(price = "980", test = true))

            shouldConformTo(specs.aperak, receipt.aperak)
            Latin1.decode(checkNotNull(receipt.aperak)) shouldContain "+9+AP'DTM+137:20261031:102'RFF+CO:PO-1001'UNT+5+1'"
        }
    })
