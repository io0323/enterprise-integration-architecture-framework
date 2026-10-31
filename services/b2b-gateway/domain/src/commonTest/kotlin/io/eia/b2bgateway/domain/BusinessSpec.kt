package io.eia.b2bgateway.domain

import io.eia.b2bgateway.domain.ack.Aperak
import io.eia.b2bgateway.domain.ack.BusinessResponse
import io.eia.b2bgateway.domain.ack.Envelopes
import io.eia.b2bgateway.domain.ack.ResponseEnvelope
import io.eia.b2bgateway.domain.ack.UtcStamp
import io.eia.b2bgateway.domain.agreement.PartnerAgreement
import io.eia.b2bgateway.domain.edifact.CharacterSet
import io.eia.b2bgateway.domain.edifact.EdifactLexer
import io.eia.b2bgateway.domain.edifact.Latin1
import io.eia.b2bgateway.domain.edifact.ServiceCharacters
import io.eia.b2bgateway.domain.order.BusinessContext
import io.eia.b2bgateway.domain.order.BusinessRule
import io.eia.b2bgateway.domain.order.CodeResolution
import io.eia.b2bgateway.domain.order.InternalItem
import io.eia.b2bgateway.domain.order.ItemCode
import io.eia.b2bgateway.domain.order.OrderLine
import io.eia.b2bgateway.domain.order.OrderRules
import io.eia.b2bgateway.domain.order.PurchaseOrder
import io.eia.b2bgateway.domain.order.PurchaseOrders
import io.eia.b2bgateway.domain.receipt.DuplicateCheck
import io.eia.b2bgateway.domain.receipt.Duplicates
import io.eia.b2bgateway.domain.receipt.PreviousReceipt
import io.eia.b2bgateway.domain.spec.RuleText
import io.eia.b2bgateway.domain.validation.InterchangeHeader
import io.eia.b2bgateway.domain.validation.PartyIdentification
import io.eia.shared.kernel.money.Currency
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

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

private val ORDER =
    PurchaseOrder(
        orderNumber = "PO-1",
        documentDate = "20261030",
        requestedDelivery = "20261105",
        buyer = "4912345000019",
        supplier = "4900000000001",
        deliveryParty = "4912345000026",
        currency = "JPY",
        lines = listOf(OrderLine("1", "4901234567894", "ABC-123", "10", "1200"), OrderLine("2", "4901234567900", null, "5", "980")),
        declaredLineCount = "2",
    )

private val RESOLUTION =
    CodeResolution(
        buyers = mapOf("4912345000019" to "cust-1"),
        deliveryParties = mapOf("4912345000026" to "addr-1"),
        items =
            mapOf(
                ItemCode.BuyerPartNumber("ABC-123") to InternalItem("prod-1", "SKU-1"),
                ItemCode.Gtin("4901234567900") to InternalItem("prod-2", "SKU-2"),
            ),
    )

private fun rules(
    order: PurchaseOrder = ORDER,
    resolution: CodeResolution = RESOLUTION,
    received: Boolean = false,
): List<BusinessRule> = OrderRules.check(order, BusinessContext(AGREEMENT, resolution, received))

class BusinessSpec :
    FunSpec({
        context("注文の取り出し") {
            test("位置で読み、明細に PIA・QTY・PRI を付け、小数点を . にそろえる") {
                val text =
                    "UNA:+,? 'UNH+1+ORDERS:D:96A:UN'BGM+220+PO-1+9'DTM+137:20261030:102'DTM+2:20261105:102'NAD+BY+B::9'NAD+SU+S::9'" +
                        "NAD+DP+D::9'CUX+2:JPY:9'LIN+1++G1:EN'PIA+1+P1:BP'QTY+21:10'PRI+AAA:12,5'" +
                        "LIN+2++G2:EN'QTY+21:5'PRI+AAA:980'UNS+S'CNT+2:2'UNT+18+1'"

                PurchaseOrders.extract(EdifactLexer.read(text).segments, ',') shouldBe
                    PurchaseOrder(
                        orderNumber = "PO-1",
                        documentDate = "20261030",
                        requestedDelivery = "20261105",
                        buyer = "B",
                        supplier = "S",
                        deliveryParty = "D",
                        currency = "JPY",
                        lines = listOf(OrderLine("1", "G1", "P1", "10", "12.5"), OrderLine("2", "G2", null, "5", "980")),
                        declaredLineCount = "2",
                    )
            }

            test("任意の納期・届け先・件数がなければ null。商品のコードは取引先の商品コード、なければ GTIN") {
                val order = PurchaseOrders.extract(EdifactLexer.read("UNA:+.? 'BGM+220+PO-2'LIN+1++G1:EN'").segments, '.')

                order.requestedDelivery shouldBe null
                order.deliveryParty shouldBe null
                order.declaredLineCount shouldBe null
                order.lines.single().itemCode shouldBe ItemCode.Gtin("G1")
                ORDER.lines[0].itemCode shouldBe ItemCode.BuyerPartNumber("ABC-123")
            }
        }

        context("業務の規則(contracts/files/edi/edifact-orders.v1.yaml の businessRules)") {
            test("対応表・合意・整合のどれにも当たらなければ違反なし") {
                rules().shouldBeEmpty()
            }

            test("対応表にない買い手・届け先・商品(届け先がなければ引かない)") {
                rules(resolution = CodeResolution()) shouldContainExactly
                    listOf(BusinessRule.UNKNOWN_BUYER, BusinessRule.UNKNOWN_DELIVERY_PARTY, BusinessRule.UNKNOWN_ITEM)
                rules(order = ORDER.copy(deliveryParty = null), resolution = RESOLUTION.copy(deliveryParties = emptyMap())).shouldBeEmpty()
            }

            test("売り手が自社でない・合意していない通貨(通貨がわからなければ単価の桁は見ない)") {
                rules(order = ORDER.copy(supplier = "4999999999999")) shouldContainExactly listOf(BusinessRule.SUPPLIER_IS_NOT_RECIPIENT)
                rules(order = ORDER.copy(currency = "USD", lines = ORDER.lines.map { it.copy(unitPrice = "1.25") })) shouldContainExactly
                    listOf(BusinessRule.CURRENCY_NOT_AGREED)
            }

            test("納期が注文日より前・注文番号の重複・件数の不一致・明細番号の重複") {
                rules(order = ORDER.copy(requestedDelivery = "20261029")) shouldContainExactly
                    listOf(BusinessRule.DELIVERY_BEFORE_ORDER_DATE)
                rules(received = true) shouldContainExactly listOf(BusinessRule.DUPLICATE_ORDER_NUMBER)
                rules(order = ORDER.copy(declaredLineCount = "3")) shouldContainExactly listOf(BusinessRule.LINE_COUNT_MISMATCH)
                rules(order = ORDER.copy(lines = ORDER.lines.map { it.copy(lineNumber = "1") })) shouldContainExactly
                    listOf(BusinessRule.DUPLICATE_LINE_NUMBER)
            }

            test("数量は正の整数、単価は 0 以上で通貨の小数桁数以内(JPY は 0 桁)") {
                listOf("0", "-1", "1.5", "01").forEach {
                    rules(
                        order = ORDER.copy(lines = listOf(ORDER.lines[1].copy(quantity = it)), declaredLineCount = null),
                    ) shouldContainExactly
                        listOf(BusinessRule.INVALID_QUANTITY)
                }
                listOf("-1", "980.5", "1.").forEach {
                    rules(
                        order = ORDER.copy(lines = listOf(ORDER.lines[1].copy(unitPrice = it)), declaredLineCount = null),
                    ) shouldContainExactly
                        listOf(BusinessRule.INVALID_PRICE)
                }
                rules(order = ORDER.copy(lines = listOf(ORDER.lines[1].copy(unitPrice = "0")), declaredLineCount = null)).shouldBeEmpty()
            }

            test("違反は仕様の規則の順に並べる") {
                rules(
                    order = ORDER.copy(currency = "USD", supplier = "X"),
                    resolution = CodeResolution(),
                    received = true,
                ) shouldContainExactly
                    listOf(
                        BusinessRule.UNKNOWN_BUYER,
                        BusinessRule.UNKNOWN_DELIVERY_PARTY,
                        BusinessRule.SUPPLIER_IS_NOT_RECIPIENT,
                        BusinessRule.UNKNOWN_ITEM,
                        BusinessRule.CURRENCY_NOT_AGREED,
                        BusinessRule.DUPLICATE_ORDER_NUMBER,
                    )
            }
        }

        context("交換の重複(EDI の層での冪等。ADR-0041 §11)") {
            test("初めて・同じ中身の送り直し・管理番号の使い回し") {
                val previous = PreviousReceipt("abc", "doc-1")

                Duplicates.decide(null, "abc") shouldBe DuplicateCheck.First
                Duplicates.decide(previous, "abc").shouldBeInstanceOf<DuplicateCheck.Resend>().previous shouldBe previous
                Duplicates.decide(previous, "def") shouldBe DuplicateCheck.Reused
            }
        }

        context("APERAK(ADR-0041 §5)") {
            val header =
                InterchangeHeader(
                    ServiceCharacters.FALLBACK,
                    CharacterSet.UNOA,
                    "3",
                    AGREEMENT.sender,
                    AGREEMENT.recipient,
                    "ICR-1",
                    test = false,
                )
            val envelope = ResponseEnvelope("A-7", "1", Instant.parse("2026-10-31T09:05:00Z"))
            val spec = TestSpecs.contrl.copy(message = TestSpecs.contrl.message.copy(type = "APERAK", release = "96A"))

            fun aperak(
                responses: List<BusinessResponse>,
                test: Boolean = false,
            ) = Latin1.decode(Envelopes.write(header, Aperak.build(spec, header.copy(test = test), responses, envelope)))

            test("拒否は 4343 = RE と、理由ごとの ERC(3055 = ZZZ)と FTX(AAO)。受け付けは AP で ERC を付けない。注文の参照は RFF+CO") {
                aperak(
                    listOf(
                        BusinessResponse("PO-1", listOf(RuleText("ORD001", "UNKNOWN BUYER"), RuleText("ORD004", "UNKNOWN ITEM"))),
                        BusinessResponse("PO+2", emptyList()),
                    ),
                ) shouldBe
                    "UNA:+.? 'UNB+UNOA:3+4900000000001:14+4912345000019:14+261031:0905+A-7'" +
                    "UNH+1+APERAK:D:96A:UN'BGM+23+A-7-1+9+RE'DTM+137:20261031:102'RFF+CO:PO-1'" +
                    "ERC+ORD001::ZZZ'FTX+AAO+++UNKNOWN BUYER'ERC+ORD004::ZZZ'FTX+AAO+++UNKNOWN ITEM'UNT+9+1'" +
                    "UNH+2+APERAK:D:96A:UN'BGM+23+A-7-2+9+AP'DTM+137:20261031:102'RFF+CO:PO?+2'UNT+5+2'UNZ+2+A-7'"
            }

            test("試験の交換には UNB の 0035 = 1 を付ける。応答する注文がなければ作らない") {
                aperak(listOf(BusinessResponse("PO-1", emptyList())), test = true).substringBefore("'UNH") shouldBe
                    "UNA:+.? 'UNB+UNOA:3+4900000000001:14+4912345000019:14+261031:0905+A-7++++++1"
                shouldThrow<IllegalArgumentException> { Aperak.build(spec, header, emptyList(), envelope) }
            }

            test("暦の日付(CCYYMMDD)は UTC") {
                UtcStamp.calendarDate(Instant.parse("2026-12-31T23:59:59Z")) shouldBe "20261231"
                UtcStamp.calendarDate(Instant.parse("1999-01-01T00:00:00Z")) shouldBe "19990101"
            }
        }
    })
